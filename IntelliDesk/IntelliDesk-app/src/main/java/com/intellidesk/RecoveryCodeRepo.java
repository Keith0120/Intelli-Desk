package com.intellidesk;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface RecoveryCodeRepo extends JpaRepository<RecoveryCode, Long> {
  Optional<RecoveryCode> findByUserIdAndHash(Long userId, String hash);
  void deleteByUserId(Long userId);
  long countByUserId(Long userId);
}
