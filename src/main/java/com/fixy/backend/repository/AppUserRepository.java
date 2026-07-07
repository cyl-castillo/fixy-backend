package com.fixy.backend.repository;

import com.fixy.backend.model.AppUser;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface AppUserRepository extends JpaRepository<AppUser, Long> {
  Optional<AppUser> findByGoogleSub(String googleSub);
}
