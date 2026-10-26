package com.hesta.backend.repository;

import com.hesta.backend.entity.User;
import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.enums.AuthProvider;
import com.hesta.backend.enums.PlatformRole;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.stereotype.Repository;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface UserRepository extends JpaRepository<User, UUID>, JpaSpecificationExecutor<User> {
    Optional<User> findByEmail(String email);
    boolean existsByEmail(String email);
    Optional<User> findByGoogleUid(String googleUid);

    long countByStatus(AccountStatus status);

    long countByPlatformRole(PlatformRole platformRole);

    long countByProvider(AuthProvider provider);

    long countByCreatedAtAfter(OffsetDateTime dateTime);

    @Query("SELECT u.createdAt FROM User u ORDER BY u.createdAt ASC")
    List<OffsetDateTime> findAllCreatedDates();
}
