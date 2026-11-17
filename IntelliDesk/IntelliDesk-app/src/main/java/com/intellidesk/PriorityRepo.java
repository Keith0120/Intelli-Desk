package com.intellidesk;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
public interface PriorityRepo extends JpaRepository<PriorityPolicy,Long> {
  Optional<PriorityPolicy> findByNameIgnoreCase(String name);
  Optional<PriorityPolicy> findFirstByOrderByRankAsc();
}
