package com.hesta.backend.repository;

import com.hesta.backend.entity.MemberRoomAccess;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface MemberRoomAccessRepository extends JpaRepository<MemberRoomAccess, UUID> {

    List<MemberRoomAccess> findByMemberId(UUID memberId);

    long countByMemberId(UUID memberId);

    @Query("SELECT mra FROM MemberRoomAccess mra " +
           "JOIN FETCH mra.room r " +
           "WHERE mra.member.home.id = :homeId AND mra.member.user.id = :userId")
    List<MemberRoomAccess> findByHomeIdAndUserId(@Param("homeId") UUID homeId, @Param("userId") UUID userId);

    Optional<MemberRoomAccess> findByMemberIdAndRoomId(UUID memberId, UUID roomId);

    @Modifying
    @Query("DELETE FROM MemberRoomAccess mra WHERE mra.member.id = :memberId")
    void deleteByMemberId(@Param("memberId") UUID memberId);
}

