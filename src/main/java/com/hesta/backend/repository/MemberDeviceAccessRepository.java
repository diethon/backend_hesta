package com.hesta.backend.repository;

import com.hesta.backend.entity.MemberDeviceAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MemberDeviceAccessRepository extends JpaRepository<MemberDeviceAccess, UUID> {

    List<MemberDeviceAccess> findByMemberId(UUID memberId);

    @Query("SELECT mda FROM MemberDeviceAccess mda " +
           "JOIN FETCH mda.device d " +
           "WHERE mda.member.home.id = :homeId AND mda.member.user.id = :userId")
    List<MemberDeviceAccess> findByHomeIdAndUserId(@Param("homeId") UUID homeId, @Param("userId") UUID userId);

    Optional<MemberDeviceAccess> findByMemberIdAndDeviceId(UUID memberId, UUID deviceId);

    @Modifying
    @Query("DELETE FROM MemberDeviceAccess mda WHERE mda.member.id = :memberId")
    void deleteByMemberId(@Param("memberId") UUID memberId);
}
