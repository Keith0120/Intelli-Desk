package com.intellidesk;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Encrypts TOTP seeds at rest. The key lives outside the database and must be kept across restarts. */
@Component
public class TotpSecretVault {
  private final SecretKeySpec key;
  private final SecureRandom random = new SecureRandom();
  final boolean generatedKey;
  public TotpSecretVault(@Value("${intellidesk.totp.key:}") String configured,
                         @Value("${intellidesk.totp.key-file:.intellidesk/totp.key}") String keyFile) {
    try {
      byte[] bytes; boolean generated = false;
      if (!configured.isBlank()) bytes = Base64.getDecoder().decode(configured.trim());
      else {
        Path path = Path.of(keyFile).toAbsolutePath();
        Files.createDirectories(path.getParent());
        if (!Files.exists(path)) {
          byte[] candidate = new byte[32]; random.nextBytes(candidate);
          try {
            if (path.getFileSystem().supportedFileAttributeViews().contains("posix"))
              Files.createFile(path, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
            else Files.createFile(path);
            Files.writeString(path, Base64.getEncoder().encodeToString(candidate), StandardOpenOption.WRITE);
            generated = true;
          } catch (java.nio.file.FileAlreadyExistsException ignored) { /* Another instance created it. */ }
        }
        bytes = Base64.getDecoder().decode(Files.readString(path).trim());
      }
      if (bytes.length != 32) throw new IllegalArgumentException("An AES-256 key must contain 32 bytes");
      key = new SecretKeySpec(bytes, "AES"); generatedKey = generated;
    } catch (Exception e) { throw new IllegalStateException("Cannot load the authenticator encryption key. Check INTELLIDESK_TOTP_KEY or the key file.", e); }
  }
  public String encrypt(String secret) {
    try {
      byte[] iv = new byte[12]; random.nextBytes(iv);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
      byte[] encrypted = cipher.doFinal(secret.getBytes(StandardCharsets.US_ASCII));
      return Base64.getEncoder().encodeToString(ByteBuffer.allocate(iv.length + encrypted.length).put(iv).put(encrypted).array());
    } catch (Exception e) { throw new IllegalStateException("Could not protect authenticator data", e); }
  }
  public String decrypt(String encrypted) {
    try {
      ByteBuffer buffer = ByteBuffer.wrap(Base64.getDecoder().decode(encrypted));
      byte[] iv = new byte[12]; buffer.get(iv); byte[] bytes = new byte[buffer.remaining()]; buffer.get(bytes);
      Cipher cipher = Cipher.getInstance("AES/GCM/NoPadding");
      cipher.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, iv));
      return new String(cipher.doFinal(bytes), StandardCharsets.US_ASCII);
    } catch (Exception e) { throw new IllegalStateException("Cannot read authenticator data. Restore the original server encryption key.", e); }
  }
}
