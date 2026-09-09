package com.hesta.backend.repository;

import com.hesta.backend.entity.HomeInvitation;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface HomeInvitationRepository extends JpaRepository<HomeInvitation, UUID> {
    Optional<HomeInvitation> findByInviteCode(String inviteCode);
    Optional<HomeInvitation> findByInviteToken(String inviteToken);
}
