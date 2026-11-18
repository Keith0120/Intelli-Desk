package com.intellidesk;

import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.intellidesk.Enums.Role;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpSession;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.server.ResponseStatusException;

/** Standalone integration checks: isolated H2 database, real Spring services and transactions, no user accounts changed. */
public class AuthenticatorChecks {
  static int assertions;
  static void check(boolean ok, String message) { assertions++; if (!ok) throw new AssertionError(message); }
  interface Action { void run() throws Exception; }
  static void status(int status, Action action) throws Exception {
    try { action.run(); throw new AssertionError("Expected status " + status); }
    catch (ResponseStatusException e) { check(e.getStatusCode().value() == status, "Unexpected status: " + e.getStatusCode()); }
  }
  static final class Session {
    final Map<String,Object> data = new ConcurrentHashMap<>();
    final AtomicInteger rotations = new AtomicInteger();
    final HttpSession session = (HttpSession) Proxy.newProxyInstance(HttpSession.class.getClassLoader(), new Class[]{HttpSession.class}, (proxy,m,a) -> {
      return switch(m.getName()) {
        case "getAttribute" -> data.get(a[0]);
        case "setAttribute" -> { data.put((String)a[0],a[1]); yield null; }
        case "removeAttribute" -> { data.remove(a[0]); yield null; }
        case "getId" -> "test-session-" + rotations.get();
        case "invalidate" -> { data.clear(); yield null; }
        case "toString" -> "Isolated test session";
        case "hashCode" -> System.identityHashCode(proxy);
        case "equals" -> proxy == a[0];
        default -> m.getReturnType() == boolean.class ? false : m.getReturnType() == int.class ? 0 : m.getReturnType() == long.class ? 0L : null;
      };
    });
    final HttpServletRequest request = (HttpServletRequest) Proxy.newProxyInstance(HttpServletRequest.class.getClassLoader(), new Class[]{HttpServletRequest.class}, (proxy,m,a) -> {
      if (m.getName().equals("changeSessionId")) return "test-session-" + rotations.incrementAndGet();
      return null;
    });
  }
  static User user(UserRepo users, BCryptPasswordEncoder enc, String name, Role role) {
    User u = new User(); u.name = name; u.email = name; u.role = role; u.passwordHash = enc.encode("isolated-test-password");
    return users.saveAndFlush(u);
  }
  static Session login(AuthController controller, User user) {
    Session s = new Session();
    Map<String,Object> r = controller.login(new AuthController.Login(user.email,"isolated-test-password",user.role),s.session,s.request);
    check(Boolean.FALSE.equals(r.get("verified")), "Password alone must not authenticate"); return s;
  }
  static String code(String secret) { return Totp.codeAt(secret,Instant.now().getEpochSecond()/30,6); }
  static String wrong(String secret) {
    for(int n=0;n<100;n++) { String c=String.format(java.util.Locale.ROOT,"%06d",n); if(Totp.matchingStep(secret,c,Instant.now(),null)<0)return c; }
    throw new AssertionError();
  }
  @SuppressWarnings("unchecked")
  public static void main(String[] args) throws Exception {
    String rfcSecret=Totp.base32("12345678901234567890".getBytes(StandardCharsets.US_ASCII));
    long[] times={59L,1111111109L,1111111111L,1234567890L,2000000000L,20000000000L};
    String[] expected={"94287082","07081804","14050471","89005924","69279037","65353130"};
    for(int i=0;i<times.length;i++)check(Totp.codeAt(rfcSecret,times[i]/30,8).equals(expected[i]),"RFC 6238 vector " + i);
    long now=Instant.now().getEpochSecond()/30; String current=Totp.codeAt(rfcSecret,now,6);
    check(Totp.matchingStep(rfcSecret,current,Instant.ofEpochSecond(now*30),null)==now,"Current TOTP");
    check(Totp.matchingStep(rfcSecret,current,Instant.ofEpochSecond((now+3)*30),null)<0,"Expired TOTP rejected");
    check(Totp.matchingStep(rfcSecret,current,Instant.ofEpochSecond(now*30),now)<0,"Replayed TOTP rejected");
    check(Totp.matchingStep(rfcSecret,"12345",Instant.now(),null)<0,"Malformed TOTP rejected");
    String uri=Totp.uri("qa+one@example.invalid",rfcSecret);
    byte[] png=Base64.getDecoder().decode(Totp.qrDataUrl(uri).substring("data:image/png;base64,".length()));
    var image=javax.imageio.ImageIO.read(new java.io.ByteArrayInputStream(png));
    int[] pixels=image.getRGB(0,0,image.getWidth(),image.getHeight(),null,0,image.getWidth());
    var bitmap=new com.google.zxing.BinaryBitmap(new com.google.zxing.common.HybridBinarizer(
        new com.google.zxing.RGBLuminanceSource(image.getWidth(),image.getHeight(),pixels)));
    check(new com.google.zxing.qrcode.QRCodeReader().decode(bitmap).getText().equals(uri),"QR round trip with account and issuer");
    Path key=Files.createTempDirectory("intellidesk-auth-checks-").resolve("totp.key");
    TotpSecretVault v1=new TotpSecretVault("",key.toString()); String encrypted=v1.encrypt(rfcSecret);
    TotpSecretVault v2=new TotpSecretVault("",key.toString());
    check(!encrypted.contains(rfcSecret)&&v2.decrypt(encrypted).equals(rfcSecret),"Encrypted seed survives key reload");
    byte[] tampered=Base64.getDecoder().decode(encrypted);tampered[tampered.length-1]^=1;
    try{v2.decrypt(Base64.getEncoder().encodeToString(tampered));throw new AssertionError("Tampering accepted");}catch(IllegalStateException expectedFailure){assertions++;}

    SpringApplication app=new SpringApplication(IntelliDeskApplication.class);app.setWebApplicationType(WebApplicationType.NONE);
    try(ConfigurableApplicationContext ctx=app.run("--spring.profiles.active=h2",
        "--spring.datasource.url=jdbc:h2:mem:authenticator_checks;DB_CLOSE_DELAY=-1",
        "--intellidesk.two-factor=true","--intellidesk.totp.key-file="+key,"--logging.level.root=WARN","--spring.main.banner-mode=off")) {
      AuthController controller=ctx.getBean(AuthController.class); UserRepo users=ctx.getBean(UserRepo.class);
      Auth auth=ctx.getBean(Auth.class);BCryptPasswordEncoder enc=ctx.getBean(BCryptPasswordEncoder.class);
      RecoveryCodeRepo recoveries=ctx.getBean(RecoveryCodeRepo.class);
      Session anonymous=new Session();status(401,()->controller.setup(anonymous.session));status(401,()->auth.current(anonymous.session));
      for(Role role:Role.values()) {
        User u=user(users,enc,"qa-auth-"+role,role);Session s=login(controller,u);
        status(401,()->auth.current(s.session));
        Map<String,Object> info=controller.setup(s.session);String secret=(String)info.get("secret");
        check(controller.setup(s.session).get("secret").equals(secret),"Refresh keeps pending QR secret");
        check(users.findById(u.id).orElseThrow().totpSecret==null,"Unconfirmed seed is not enrolled");
        status(401,()->controller.verify(new AuthController.Verify(wrong(secret)),s.session,s.request));
        Map<String,Object> result=controller.verify(new AuthController.Verify(code(secret)),s.session,s.request);
        List<String> codes=(List<String>)result.get("recoveryCodes");
        check(codes.size()==8&&recoveries.countByUserId(u.id)==8,"Eight recovery codes generated");
        check(auth.current(s.session).id.equals(u.id),"Verified role can authenticate");
        check(s.rotations.get()>=2,"Session ID rotates on login and verification");
        User stored=users.findById(u.id).orElseThrow();
        check(!stored.totpSecret.equals(secret)&&v2.decrypt(stored.totpSecret).equals(secret),"Seed encrypted in DB");
        String json=new ObjectMapper().findAndRegisterModules().writeValueAsString(stored);
        check(!json.contains("totp")&&!json.contains("passwordHash")&&!json.contains(secret),"No authentication secrets in user API serialization");
        Session replay=login(controller,u);check(Boolean.FALSE.equals(controller.currentChallenge(replay.session).get("setupRequired")),"Existing account verifies without exposing QR");
        status(409,()->controller.setup(replay.session));
        status(401,()->controller.verify(new AuthController.Verify(code(secret)),replay.session,replay.request));
        // Model the next login window without sleeping or changing the machine clock.
        stored=users.findById(u.id).orElseThrow();stored.totpLastStep=Instant.now().getEpochSecond()/30-2;users.saveAndFlush(stored);
        Session valid=login(controller,u);controller.verify(new AuthController.Verify(code(secret)),valid.session,valid.request);
        check(auth.current(valid.session).id.equals(u.id),"Enrolled account completes a later login");
        Session recovery=login(controller,u);controller.recovery(new AuthController.Verify(codes.get(0)),recovery.session,recovery.request);
        check(recoveries.countByUserId(u.id)==7,"Recovery code consumed once");status(401,()->auth.current(recovery.session));
        String replacement=(String)controller.setup(recovery.session).get("secret");
        check(!replacement.equals(secret),"Recovery starts new phone enrollment");
        controller.verify(new AuthController.Verify(code(replacement)),recovery.session,recovery.request);
        check(recoveries.countByUserId(u.id)==8,"Replacement rotates all recovery codes");
        Session reused=login(controller,u);status(401,()->controller.recovery(new AuthController.Verify(codes.get(0)),reused.session,reused.request));
        // An account disabled after password verification must never finish MFA.
        Session disabled=login(controller,u);stored=users.findById(u.id).orElseThrow();stored.active=false;users.saveAndFlush(stored);
        status(401,()->controller.verify(new AuthController.Verify(code(replacement)),disabled.session,disabled.request));
      }
      User throttle=user(users,enc,"qa-throttle",Role.EMPLOYEE);Session t=login(controller,throttle);
      String secret=(String)controller.setup(t.session).get("secret");
      controller.verify(new AuthController.Verify(code(secret)),t.session,t.request);
      for(int i=1;i<=5;i++) { Session attempt=login(controller,throttle);int expectedStatus=i==5?429:401;
        status(expectedStatus,()->controller.verify(new AuthController.Verify(wrong(secret)),attempt.session,attempt.request)); }
      Session blocked=login(controller,throttle);status(429,()->controller.verify(new AuthController.Verify(code(secret)),blocked.session,blocked.request));
      User concurrent=user(users,enc,"qa-concurrent",Role.ADMIN);Session enroll=login(controller,concurrent);
      String shared=(String)controller.setup(enroll.session).get("secret");controller.verify(new AuthController.Verify(code(shared)),enroll.session,enroll.request);
      concurrent=users.findById(concurrent.id).orElseThrow();concurrent.totpLastStep=Instant.now().getEpochSecond()/30-2;users.saveAndFlush(concurrent);
      Session a=login(controller,concurrent),b=login(controller,concurrent);String sharedCode=code(shared);
      ExecutorService pool=Executors.newFixedThreadPool(2);
      try { List<Future<Boolean>> results=pool.invokeAll(List.of(
          ()->{try{controller.verify(new AuthController.Verify(sharedCode),a.session,a.request);return true;}catch(ResponseStatusException e){return false;}},
          ()->{try{controller.verify(new AuthController.Verify(sharedCode),b.session,b.request);return true;}catch(ResponseStatusException e){return false;}}));
        int wins=0;for(var result:results)if(result.get())wins++;check(wins==1,"Concurrent reuse admits exactly one login");
      } finally {pool.shutdownNow();}
      User expiry=user(users,enc,"qa-expiry",Role.EMPLOYEE);Session expired=login(controller,expiry);
      expired.data.put("challengeExp",Instant.now().minusSeconds(1));status(401,()->controller.setup(expired.session));
      check(!expired.data.containsKey("pendingUid"),"Expired challenge cleared");
      Session mismatch=new Session();status(400,()->controller.login(new AuthController.Login(expiry.email,"isolated-test-password",Role.ADMIN),mismatch.session,mismatch.request));
      check(!mismatch.data.containsKey("pendingUid"),"Wrong role rejected before enrollment");
    }
    System.out.println("PASS: " + assertions + " authenticator checks (RFC vectors, QR decoding, encryption, all roles, replay, recovery, lockout, concurrency, and expiry).");
  }
}
