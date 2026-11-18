package com.intellidesk;

import jakarta.persistence.*;
@Entity @Table(name="auth_recovery_codes", uniqueConstraints=@UniqueConstraint(columnNames={"user_id", "code_hash"}))
public class RecoveryCode {
  @Id @GeneratedValue(strategy=GenerationType.IDENTITY) public Long id;
  @ManyToOne(optional=false, fetch=FetchType.LAZY) @JoinColumn(name="user_id") public User user;
  @Column(name="code_hash", nullable=false, length=64) public String hash;
}
