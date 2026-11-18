package com.intellidesk;
import jakarta.persistence.*;
/** One admin-editable on/off preference, stored by key. */
@Entity @Table(name="system_settings")
public class SystemSetting {
  @Id @Column(name="setting_key",length=60) public String key;
  @Column(nullable=false) public boolean enabled;
  public SystemSetting() {}
  public SystemSetting(String key, boolean enabled) { this.key = key; this.enabled = enabled; }
}
