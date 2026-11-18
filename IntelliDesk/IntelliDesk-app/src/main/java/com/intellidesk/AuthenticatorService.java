package com.intellidesk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

@Service
public class AuthenticatorService {
  private final UserRepo users; private final RecoveryCodeRepo recovery; private final TotpSecretVault vault;
  private final SecureRandom random = new SecureRandom();
  public AuthenticatorService(UserRepo users, RecoveryCodeRepo recovery, TotpSecretVault vault) {
    this.users = users; this.recovery = recovery; this.vault = vault;
    if (vault.generatedKey && users.existsByTotpSecretIsNotNull())
      throw new IllegalStateException("Existing authenticator accounts need their original encryption key. Restore .intellidesk/totp.key before starting.");
  }
  private User locked(Long id) {
    User user = users.lockForAuthentication(id).filter(u -> u.active)
        .orElseThrow(() -> new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Sign in again."));
    if (user.totpBlockedUntil != null && user.totpBlockedUntil.isAfter(Instant.now()))
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many incorrect codes. Wait five minutes before trying again.");
    if (user.totpBlockedUntil != null) { user.totpFailures = 0; user.totpBlockedUntil = null; }
    return user;
  }
  private void failure(User user) {
    user.totpFailures = (user.totpFailures == null ? 0 : user.totpFailures) + 1;
    if (user.totpFailures >= 5) {
      user.totpBlockedUntil = Instant.now().plusSeconds(300);
      throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, "Too many incorrect codes. Wait five minutes before trying again.");
    }
    throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Incorrect or already-used code. Enter the current code from Google Authenticator.");
  }
  private void success(User user) { user.totpFailures = 0; user.totpBlockedUntil = null; }

  // Validation errors must commit the account's failure counter, including attempts from new sessions.
  @Transactional(noRollbackFor=ResponseStatusException.class)
  public List<String> enroll(Long id, String secret, String code, boolean replacement) {
    User user = locked(id);
    if (user.totpSecret != null && !replacement)
      throw new ResponseStatusException(HttpStatus.CONFLICT, "This account is already enrolled. Sign in again.");
    long step = Totp.matchingStep(secret, code, Instant.now(), null);
    if (step < 0) failure(user);
    user.totpSecret = vault.encrypt(secret); user.totpLastStep = step; success(user);
    recovery.deleteByUserId(id); recovery.flush();
    List<String> codes = new ArrayList<>();
    for (int i = 0; i < 8; i++) {
      byte[] bytes = new byte[10]; random.nextBytes(bytes);
      String raw = HexFormat.of().withUpperCase().formatHex(bytes);
      String formatted = raw.substring(0,5) + "-" + raw.substring(5,10) + "-" + raw.substring(10,15) + "-" + raw.substring(15);
      RecoveryCode item = new RecoveryCode(); item.user = user; item.hash = recoveryHash(raw); recovery.save(item);
      codes.add(formatted);
    }
    return codes;
  }
  @Transactional(noRollbackFor=ResponseStatusException.class)
  public void verify(Long id, String code) {
    User user = locked(id);
    if (user.totpSecret == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Set up Google Authenticator first.");
    long step = Totp.matchingStep(vault.decrypt(user.totpSecret), code, Instant.now(), user.totpLastStep);
    if (step < 0) failure(user);
    user.totpLastStep = step; success(user);
  }
  @Transactional(noRollbackFor=ResponseStatusException.class)
  public void consumeRecovery(Long id, String code) {
    User user = locked(id);
    String normalized = code == null ? "" : code.replace("-", "").replace(" ", "").toUpperCase(java.util.Locale.ROOT);
    RecoveryCode match = normalized.matches("[0-9A-F]{20}")
        ? recovery.findByUserIdAndHash(id, recoveryHash(normalized)).orElse(null) : null;
    if (user.totpSecret == null || match == null) failure(user);
    recovery.delete(match); success(user);
  }
  static String recoveryHash(String code) {
    try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(code.getBytes(StandardCharsets.US_ASCII))); }
    catch (java.security.NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
  }
}
