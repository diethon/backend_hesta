package com.hesta.backend.repository;

import com.hesta.backend.entity.HomeMember;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface HomeMemberRepository extends JpaRepository<HomeMember, UUID> {
    List<HomeMember> findByHomeId(UUID homeId);
    List<HomeMember> findByUserId(UUID userId);
    List<HomeMember> findByUserIdOrderByJoinedAtDesc(UUID userId);
    Optional<HomeMember> findByHomeIdAndUserId(UUID homeId, UUID userId);
    boolean existsByHomeIdAndUserId(UUID homeId, UUID userId);

    @Query("SELECT hm FROM HomeMember hm JOIN FETCH hm.user WHERE hm.home.id = :homeId")
    List<HomeMember> findByHomeIdWithUser(@Param("homeId") UUID homeId);

    @Query("SELECT hm FROM HomeMember hm JOIN FETCH hm.home WHERE hm.user.id = :userId ORDER BY hm.joinedAt DESC")
    List<HomeMember> findByUserIdWithHomeOrderByJoinedAtDesc(@Param("userId") UUID userId);
}
