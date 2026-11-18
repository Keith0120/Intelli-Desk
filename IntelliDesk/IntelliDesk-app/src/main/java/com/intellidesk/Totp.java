package com.intellidesk;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.net.URLEncoder;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.imageio.ImageIO;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

/** RFC 6238: HMAC-SHA1, six digits, 30-second periods. No secrets leave this server for QR generation. */
public final class Totp {
  private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  private static final SecureRandom RANDOM = new SecureRandom();
  private Totp() {}

  public static String newSecret() {
    byte[] bytes = new byte[20]; RANDOM.nextBytes(bytes); return base32(bytes);
  }
  static String base32(byte[] bytes) {
    StringBuilder result = new StringBuilder(); int value = 0, bits = 0;
    for (byte b : bytes) {
      value = (value << 8) | (b & 255); bits += 8;
      while (bits >= 5) { bits -= 5; result.append(ALPHABET.charAt((value >>> bits) & 31)); }
    }
    if (bits > 0) result.append(ALPHABET.charAt((value << (5 - bits)) & 31));
    return result.toString();
  }
  static byte[] decode(String secret) {
    ByteArrayOutputStream bytes = new ByteArrayOutputStream(); int value = 0, bits = 0;
    for (char c : secret.toCharArray()) {
      int digit = ALPHABET.indexOf(c);
      if (digit < 0) throw new IllegalArgumentException("Invalid Base32 secret");
      value = (value << 5) | digit; bits += 5;
      if (bits >= 8) { bits -= 8; bytes.write((value >>> bits) & 255); }
    }
    return bytes.toByteArray();
  }
  static String codeAt(String secret, long step, int digits) {
    try {
      Mac mac = Mac.getInstance("HmacSHA1");
      mac.init(new SecretKeySpec(decode(secret), "HmacSHA1"));
      byte[] hash = mac.doFinal(ByteBuffer.allocate(8).putLong(step).array());
      int offset = hash[hash.length - 1] & 15;
      int binary = ((hash[offset] & 127) << 24) | ((hash[offset+1] & 255) << 16)
          | ((hash[offset+2] & 255) << 8) | (hash[offset+3] & 255);
      int divisor = digits == 8 ? 100_000_000 : 1_000_000;
      return String.format(java.util.Locale.ROOT, "%0" + digits + "d", binary % divisor);
    } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException("TOTP is unavailable", e); }
  }
  /** Accept one adjacent time period for clock drift; return the matched counter for replay protection. */
  public static long matchingStep(String secret, String code, Instant now, Long lastStep) {
    if (code == null || !code.matches("[0-9]{6}")) return -1;
    long current = now.getEpochSecond() / 30;
    for (long step : new long[]{current, current - 1, current + 1}) {
      if (step < 0 || (lastStep != null && step <= lastStep)) continue;
      if (MessageDigest.isEqual(codeAt(secret, step, 6).getBytes(StandardCharsets.US_ASCII),
          code.getBytes(StandardCharsets.US_ASCII))) return step;
    }
    return -1;
  }
  public static String uri(String username, String secret) {
    String name = URLEncoder.encode(username.replace(':', '_'), StandardCharsets.UTF_8).replace("+", "%20");
    return "otpauth://totp/IntelliDesk:" + name + "?secret=" + secret
        + "&issuer=IntelliDesk&algorithm=SHA1&digits=6&period=30";
  }
  public static String qrDataUrl(String uri) {
    try {
      BitMatrix matrix = new QRCodeWriter().encode(uri, BarcodeFormat.QR_CODE, 280, 280,
          Map.of(EncodeHintType.MARGIN, 3));
      BufferedImage image = new BufferedImage(matrix.getWidth(), matrix.getHeight(), BufferedImage.TYPE_INT_RGB);
      for (int y = 0; y < matrix.getHeight(); y++)
        for (int x = 0; x < matrix.getWidth(); x++) image.setRGB(x, y, matrix.get(x,y) ? 0x000000 : 0xffffff);
      ByteArrayOutputStream png = new ByteArrayOutputStream(); ImageIO.write(image, "png", png);
      return "data:image/png;base64," + Base64.getEncoder().encodeToString(png.toByteArray());
    } catch (Exception e) { throw new IllegalStateException("Could not create the authenticator QR code", e); }
  }
}
