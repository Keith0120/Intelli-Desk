package com.intellidesk;
import com.intellidesk.Enums.Role;
import java.util.*;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import jakarta.persistence.LockModeType;
public interface UserRepo extends JpaRepository<User,Long> {
  Optional<User> findByEmailIgnoreCase(String e);
  List<User> findByRole(Role r);
  boolean existsByTotpSecretIsNotNull();
  @Lock(LockModeType.PESSIMISTIC_WRITE)
  @Query("select u from User u where u.id = :id")
  Optional<User> lockForAuthentication(@Param("id") Long id);
}
