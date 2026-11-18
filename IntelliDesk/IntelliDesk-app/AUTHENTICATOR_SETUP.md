# IntelliDesk Google Authenticator

This update replaces the terminal-only verification codes with TOTP codes compatible with Google Authenticator. It keeps the current usernames, passwords, roles, and ticket workflow.

## Run and enroll

1. Build and restart IntelliDesk using your normal Java/Maven workflow. The new dependency is `com.google.zxing:core:3.5.3` from Maven Central. With Maven: `mvn spring-boot:run -Dspring-boot.run.profiles=h2` for the local demo, or use your existing MySQL profile.
2. Open http://localhost:8080 and sign in with the matching role.
3. The first sign-in for an account displays a QR code. On your phone, open Google Authenticator, select **+**, then **Scan a QR code**. A manual setup key is also available.
4. Enter that account's current six-digit code and select **Confirm setup**.
5. Save the eight recovery codes. They are displayed once. Select the saved-codes checkbox and continue.
6. Future logins ask for the code shown in Google Authenticator. The app no longer sends or logs sign-in codes.

Enroll employee, technician, and admin accounts separately. For a demo on one phone, use separate entries for `james.carter`, `marcus.webb`, and `admin`. If a code was just used to finish enrollment or log in, wait until Authenticator shows the next code before using it again.

## Replace a lost phone

After entering your username, password, and role, select **Lost access to your phone? Use a recovery code**. Enter an unused saved recovery code, scan the new QR code on your replacement phone, and confirm its code. Replacement enrollment invalidates the old phone's secret and replaces all old recovery codes. A recovery code alone never grants access.

## Keep enrollment across restarts

The `h2` demo profile now stores its database under `data/intellidesk` instead of keeping it only in memory. MySQL continues to use the existing configured database. The switch from an in-memory demo starts a fresh local database; temporary rows from the old memory-only process are not migrated automatically.

Authenticator seeds are encrypted with AES-256-GCM. By default the server generates an owner-readable key at `.intellidesk/totp.key`. Keep this file together with your database backups, separately from source-control and distributable archives. Losing the key prevents the server from validating existing phone codes.

For an externally managed key, set `INTELLIDESK_TOTP_KEY` to a Base64-encoded 32-byte random key before the first enrollment. `INTELLIDESK_TOTP_KEY_FILE` can select a protected key-file path instead. Keep the same key for the lifetime of the enrolled database. Do not print, commit, or share it. Do not use the default demo passwords for a public deployment; serve deployed enrollment and login over HTTPS.

## Validation

Run `python3 scripts/check-authenticator.py`. Use `--maven /path/to/mvn`, `--repo /path/to/cache`, or `--offline` if needed. The checks use an isolated H2 database and temporary encryption key. They do not enroll, deactivate, or modify real users.

The checks cover the published RFC 6238 SHA1 vectors, QR decode round trip, encrypted seed reload and tamper rejection, password-only access denial, enrollment and repeat login for all roles, no secret fields in API user serialization, expired/replayed/malformed codes, one-use recovery and replacement enrollment, account lockouts across sessions, concurrent replay, and disabled accounts.

Five incorrect codes lock that account's verification for five minutes. Pending sign-in/setup expires after ten minutes. The existing 30-minute inactive-session timeout remains configured; an actual 30-minute browser wait has not been performed.
