package com.hesta.backend.service.impl;

import com.hesta.backend.entity.*;
import com.hesta.backend.enums.*;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.*;
import com.hesta.backend.service.EmailService;
import com.hesta.backend.service.HomeService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.*;

@Service
@RequiredArgsConstructor
public class HomeServiceImpl implements HomeService {

    private final HomeRepository homeRepository;
    private final HomeMemberRepository homeMemberRepository;
    private final HomeInvitationRepository homeInvitationRepository;
    private final UserRepository userRepository;
    private final EmailService emailService;

    @Override
    @Transactional
    public Map<String, Object> generateInvitation(UUID inviterId, UUID homeId, String email) {
        User inviter = userRepository.findById(inviterId).orElseThrow();
        Home home = homeRepository.findById(homeId).orElseThrow();

        // Check if inviter is OWNER
        HomeMember membership = homeMemberRepository.findByHomeIdAndUserId(homeId, inviterId)
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED));
        if (membership.getRole() != HomeRole.OWNER) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        String inviteCode = String.format("%06d", new Random().nextInt(999999));
        String inviteToken = UUID.randomUUID().toString();

        HomeInvitation invitation = HomeInvitation.builder()
                .home(home)
                .inviter(inviter)
                .inviteCode(inviteCode)
                .inviteToken(inviteToken)
                .targetEmail(email)
                .expiresAt(OffsetDateTime.now().plusDays(7))
                .build();

        homeInvitationRepository.save(invitation);

        boolean emailSent = false;
        if (email != null && !email.trim().isEmpty()) {
            emailSent = emailService.sendInvitationEmail(email, inviter.getFullName(), home.getName(), inviteCode, inviteToken);
        }

        Map<String, Object> result = new java.util.HashMap<>();
        result.put("inviteCode", inviteCode);
        result.put("inviteToken", inviteToken);
        result.put("expiresAt", invitation.getExpiresAt());
        result.put("emailSent", emailSent);
        return result;
    }

    @Override
    @Transactional
    public void joinHome(UUID userId, String codeOrToken) {
        User user = userRepository.findById(userId).orElseThrow();

        HomeInvitation invitation = homeInvitationRepository.findByInviteCode(codeOrToken)
                .orElseGet(() -> homeInvitationRepository.findByInviteToken(codeOrToken)
                        .orElseThrow(() -> new AppException(ErrorCode.INVALID_CREDENTIALS)));

        if (invitation.getExpiresAt().isBefore(OffsetDateTime.now())) {
            invitation.setStatus(InvitationStatus.EXPIRED);
            homeInvitationRepository.save(invitation);
            throw new AppException(ErrorCode.INVALID_CREDENTIALS);
        }

        if (invitation.getStatus() != InvitationStatus.PENDING) {
            throw new AppException(ErrorCode.INVALID_CREDENTIALS);
        }

        if (homeMemberRepository.existsByHomeIdAndUserId(invitation.getHome().getId(), userId)) {
            throw new RuntimeException("Bạn đã là thành viên của nhà này");
        }

        HomeMember member = HomeMember.builder()
                .home(invitation.getHome())
                .user(user)
                .role(HomeRole.MEMBER)
                .invitedBy(invitation.getInviter())
                .build();
        homeMemberRepository.save(member);

        invitation.setStatus(InvitationStatus.ACCEPTED);
        homeInvitationRepository.save(invitation);
    }

    @Override
    @Transactional(readOnly = true)
    public Object getHomeMembers(UUID userId, UUID homeId) {
        // Just verify access
        homeMemberRepository.findByHomeIdAndUserId(homeId, userId)
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED));

        List<HomeMember> members = homeMemberRepository.findByHomeIdWithUser(homeId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (HomeMember m : members) {
            result.add(Map.of(
                    "id", m.getUser().getId(),
                    "fullName", m.getUser().getFullName(),
                    "email", m.getUser().getEmail(),
                    "avatarUrl", m.getUser().getAvatarUrl() != null ? m.getUser().getAvatarUrl() : "",
                    "role", m.getRole().name(),
                    "joinedAt", m.getJoinedAt()
            ));
        }
        return result;
    }

    @Override
    @Transactional(readOnly = true)
    public Object getUserHomes(UUID userId) {
        List<HomeMember> memberships = homeMemberRepository.findByUserIdWithHomeOrderByJoinedAtDesc(userId);
        List<Map<String, Object>> result = new ArrayList<>();
        for (HomeMember m : memberships) {
            result.add(Map.of(
                    "homeId", m.getHome().getId(),
                    "homeName", m.getHome().getName(),
                    "role", m.getRole().name()
            ));
        }
        return result;
    }

    @Override
    @Transactional
    public void updateMemberRole(UUID ownerId, UUID homeId, UUID memberId, HomeRole newRole) {
        HomeMember owner = homeMemberRepository.findByHomeIdAndUserId(homeId, ownerId)
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED));
        if (owner.getRole() != HomeRole.OWNER) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        HomeMember target = homeMemberRepository.findByHomeIdAndUserId(homeId, memberId)
                .orElseThrow(() -> new RuntimeException("Member not found"));

        if (ownerId.equals(memberId)) {
            throw new RuntimeException("Cannot change your own role this way");
        }

        target.setRole(newRole);
        homeMemberRepository.save(target);
    }

    @Override
    @Transactional
    public void removeMember(UUID ownerId, UUID homeId, UUID memberId) {
        HomeMember owner = homeMemberRepository.findByHomeIdAndUserId(homeId, ownerId)
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED));
        if (owner.getRole() != HomeRole.OWNER) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        HomeMember target = homeMemberRepository.findByHomeIdAndUserId(homeId, memberId)
                .orElseThrow(() -> new RuntimeException("Member not found"));

        if (ownerId.equals(memberId)) {
            throw new RuntimeException("Cannot remove yourself");
        }

        homeMemberRepository.delete(target);
    }

    @Override
    @Transactional
    public Map<String, Object> createHome(UUID userId, String homeName) {
        User user = userRepository.findById(userId).orElseThrow();
        
        Home home = Home.builder()
                .name(homeName)
                .createdBy(user)
                .build();
        Home savedHome = homeRepository.save(home);

        HomeMember owner = HomeMember.builder()
                .home(savedHome)
                .user(user)
                .role(HomeRole.OWNER)
                .status(MemberStatus.ACTIVE)
                .allowVoiceOverride(true)
                .allowSceneCreation(true)
                .allowRemoteControl(true)
                .build();
        homeMemberRepository.save(owner);

        return Map.of(
                "homeId", savedHome.getId(),
                "homeName", savedHome.getName(),
                "role", "OWNER"
        );
    }
}
