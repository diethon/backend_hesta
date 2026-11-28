package com.hesta.backend.repository;

import com.hesta.backend.entity.InstallationContract;
import com.hesta.backend.enums.InstallationContractStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface InstallationContractRepository extends JpaRepository<InstallationContract, UUID> {

    Optional<InstallationContract> findByCode(String code);

    Optional<InstallationContract> findByClaimToken(String claimToken);

    boolean existsByCode(String code);

    @Query("""
        SELECT c FROM InstallationContract c
        LEFT JOIN FETCH c.user
        LEFT JOIN FETCH c.home
        WHERE c.user.id = :userId
           OR (c.user IS NULL AND LOWER(c.customerEmail) = LOWER(:email))
        ORDER BY c.createdAt DESC
    """)
    List<InstallationContract> findAccessibleByUser(@Param("userId") UUID userId, @Param("email") String email);

    @Query("""
        SELECT c FROM InstallationContract c
        LEFT JOIN FETCH c.user
        LEFT JOIN FETCH c.home
        WHERE (:status IS NULL OR c.status = :status)
          AND (:pattern IS NULL
               OR LOWER(c.code) LIKE :pattern
               OR LOWER(c.customerName) LIKE :pattern
               OR LOWER(c.customerPhone) LIKE :pattern
               OR LOWER(c.customerEmail) LIKE :pattern
               OR LOWER(COALESCE(c.homeName, '')) LIKE :pattern)
        ORDER BY c.createdAt DESC
    """)
    List<InstallationContract> searchForAdmin(
            @Param("status") InstallationContractStatus status,
            @Param("pattern") String pattern
    );

    long countByStatus(InstallationContractStatus status);
}
