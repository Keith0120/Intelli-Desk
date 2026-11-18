package com.intellidesk;
import static com.intellidesk.Enums.Role.ADMIN;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import jakarta.servlet.http.HttpSession;

/** Admin-only system preferences. Unknown keys are rejected; missing keys default to enabled. */
@RestController @RequestMapping("/api/settings")
public class SettingsController {
  static final String TICKET_ALERTS = "ticketUpdateAlerts";
  /** Role-based access is not a setting: the server always enforces it. */
  static final List<String> KEYS = List.of(TICKET_ALERTS);
  private final Auth auth; private final SystemSettingRepo repo;
  public SettingsController(Auth auth, SystemSettingRepo repo) { this.auth = auth; this.repo = repo; }

  @GetMapping
  public Map<String,Boolean> get(HttpSession s) { auth.require(s, ADMIN); return current(); }

  @PutMapping
  public Map<String,Boolean> save(@RequestBody Map<String,Boolean> body, HttpSession s) {
    auth.require(s, ADMIN);
    if (body == null || body.isEmpty()) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "No settings provided");
    for (var e : body.entrySet()) {
      if (!KEYS.contains(e.getKey())) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Unknown setting: " + e.getKey());
      if (e.getValue() == null) throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "Setting " + e.getKey() + " needs true or false");
    }
    body.forEach((k, v) -> repo.save(new SystemSetting(k, v)));
    return current();
  }

  private Map<String,Boolean> current() {
    Map<String,Boolean> m = new LinkedHashMap<>();
    for (String k : KEYS) m.put(k, repo.findById(k).map(x -> x.enabled).orElse(true));
    return m;
  }
}
