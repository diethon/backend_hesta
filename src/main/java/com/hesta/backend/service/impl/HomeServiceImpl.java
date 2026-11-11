package com.hesta.backend.service.impl;



import com.hesta.backend.entity.*;

import com.hesta.backend.enums.*;

import com.hesta.backend.exception.AppException;

import com.hesta.backend.exception.ErrorCode;

import com.hesta.backend.repository.*;
import java.util.stream.Collectors;

import com.hesta.backend.service.EmailService;

import com.hesta.backend.service.HomeService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import com.hesta.backend.event.MemberAccessChangedEvent;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import org.springframework.transaction.annotation.Transactional;



import java.time.OffsetDateTime;

import java.util.*;



@Service
@RequiredArgsConstructor
@Slf4j
public class HomeServiceImpl implements HomeService {



    private final HomeRepository homeRepository;

    private final HomeMemberRepository homeMemberRepository;

    private final HomeInvitationRepository homeInvitationRepository;

    private final UserRepository userRepository;

    private final EmailService emailService;

    private final com.hesta.backend.repository.RoomRepository roomRepository;

    private final MemberRoomAccessRepository memberRoomAccessRepository;

    private final MemberDeviceAccessRepository memberDeviceAccessRepository;

    private final DeviceRepository deviceRepository;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;

    private final ApplicationEventPublisher applicationEventPublisher;

    @org.springframework.beans.factory.annotation.Value("${access.custom-scope.enabled:false}")
    private boolean customScopeEnabled;



    @Override

    @Transactional

    public Map<String, Object> generateInvitation(UUID inviterId, UUID homeId, String email) {
        return generateInvitation(inviterId, homeId, email, null);
    }

    @Override
    @Transactional
    public Map<String, Object> generateInvitation(UUID inviterId, UUID homeId, String email, com.hesta.backend.dto.access.CreateInvitationRequest request) {
        User inviter = userRepository.findById(inviterId).orElseThrow();
        Home home = homeRepository.findById(homeId).orElseThrow();

        HomeMember membership = homeMemberRepository.findByHomeIdAndUserId(homeId, inviterId)
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED));
        if (membership.getRole() != HomeRole.OWNER) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        String targetEmail = null;
        if (request != null && request.getEmail() != null && !request.getEmail().trim().isEmpty()) {
            targetEmail = request.getEmail().trim();
        } else if (email != null && !email.trim().isEmpty()) {
            targetEmail = email.trim();
        }

        if (targetEmail != null) {
            if (!targetEmail.matches("^[A-Za-z0-9+_.-]+@(.+)$")) {
                throw new AppException(ErrorCode.INVALID_EMAIL);
            }
            Optional<User> existingUser = userRepository.findByEmail(targetEmail);
            if (existingUser.isPresent() && homeMemberRepository.existsByHomeIdAndUserId(homeId, existingUser.get().getId())) {
                throw new AppException(ErrorCode.MEMBER_ALREADY_EXISTS);
            }
            List<HomeInvitation> pendingInvites = homeInvitationRepository.findByHomeIdAndTargetEmailAndStatus(
                    homeId, targetEmail, InvitationStatus.PENDING);
            boolean hasActiveInvite = pendingInvites.stream()
                    .anyMatch(inv -> inv.getExpiresAt().isAfter(OffsetDateTime.now()));
            if (hasActiveInvite) {
                throw new AppException(ErrorCode.INVITATION_ALREADY_EXISTS);
            }
        }

        AccessScope scope = request != null ? request.getEffectiveScope() : AccessScope.ALL;
        if (scope == null) {
            scope = AccessScope.ALL;
        }

        String accessPayload = null;
        if (scope == AccessScope.CUSTOM && request != null) {
            if (!customScopeEnabled) {
                throw new AppException(ErrorCode.FEATURE_DISABLED, "Chưa bật quyền tùy chỉnh");
            }
            try {
                Map<UUID, Room> homeRoomsById = roomRepository.findByHomeId(homeId).stream()
                        .collect(Collectors.toMap(Room::getId, r -> r));
                Map<UUID, Device> homeDevicesById = deviceRepository.findByHomeIdWithRoomAndNode(homeId).stream()
                        .filter(d -> !d.isDeleted())
                        .collect(Collectors.toMap(Device::getId, d -> d));

                List<com.hesta.backend.dto.access.MemberRoomAccessDto> reqRooms = request.getEffectiveRooms();
                List<com.hesta.backend.dto.access.MemberDeviceAccessDto> reqDevices = request.getEffectiveDevices();

                List<String> offending = new ArrayList<>();
                Set<UUID> seenRooms = new HashSet<>();
                if (reqRooms != null) {
                    for (var r : reqRooms) {
                        if (r.getRoomId() == null || r.getLevel() == null) {
                            offending.add("room:invalid");
                            continue;
                        }
                        if (!seenRooms.add(r.getRoomId())) {
                            offending.add("duplicate_room:" + r.getRoomId());
                        }
                        if (!homeRoomsById.containsKey(r.getRoomId())) {
                            offending.add("room:" + r.getRoomId());
                        }
                    }
                }
                Set<UUID> seenDevices = new HashSet<>();
                if (reqDevices != null) {
                    for (var d : reqDevices) {
                        if (d.getDeviceId() == null || d.getLevel() == null) {
                            offending.add("device:invalid");
                            continue;
                        }
                        if (!seenDevices.add(d.getDeviceId())) {
                            offending.add("duplicate_device:" + d.getDeviceId());
                        }
                        if (!homeDevicesById.containsKey(d.getDeviceId())) {
                            offending.add("device:" + d.getDeviceId());
                        }
                    }
                }
                if (!offending.isEmpty()) {
                    throw new AppException(ErrorCode.INVALID_ACCESS_PAYLOAD, "Dữ liệu phân quyền không hợp lệ: " + offending);
                }

                Map<String, Object> payloadMap = new LinkedHashMap<>();
                payloadMap.put("rooms", reqRooms != null ? reqRooms : List.of());
                payloadMap.put("devices", reqDevices != null ? reqDevices : List.of());
                accessPayload = objectMapper.writeValueAsString(payloadMap);
            } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
                throw new AppException(ErrorCode.INVALID_REQUEST);
            }
        }

        String inviteCode = String.format("%06d", new Random().nextInt(999999));
        String inviteToken = UUID.randomUUID().toString();

        HomeInvitation invitation = HomeInvitation.builder()
                .home(home)
                .inviter(inviter)
                .inviteCode(inviteCode)
                .inviteToken(inviteToken)
                .targetEmail(targetEmail)
                .accessScope(scope)
                .accessPayload(accessPayload)
                .expiresAt(OffsetDateTime.now().plusDays(7))
                .build();

        homeInvitationRepository.save(invitation);

        boolean emailSent = false;
        if (targetEmail != null && !targetEmail.isEmpty()) {
            emailSent = emailService.sendInvitationEmail(targetEmail, inviter.getFullName(), home.getName(), inviteCode, inviteToken);
        }

        Map<String, Object> result = new HashMap<>();
        result.put("inviteCode", inviteCode);
        result.put("inviteToken", inviteToken);
        result.put("expiresAt", invitation.getExpiresAt());
        result.put("emailSent", emailSent);
        result.put("accessScope", scope.name());
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
            throw new AppException(ErrorCode.MEMBER_ALREADY_EXISTS);
        }

        AccessScope scopeToApply = invitation.getAccessScope() != null ? invitation.getAccessScope() : AccessScope.ALL;
        if (scopeToApply == AccessScope.CUSTOM && !customScopeEnabled) {
            log.warn("Custom scope is disabled at join time; falling back to ALL for user {} joining home {}",
                    userId, invitation.getHome().getId());
            scopeToApply = AccessScope.ALL;
        }

        HomeMember member = HomeMember.builder()
                .home(invitation.getHome())
                .user(user)
                .role(HomeRole.MEMBER)
                .invitedBy(invitation.getInviter())
                .accessScope(scopeToApply)
                .build();

        HomeMember savedMember = homeMemberRepository.save(member);

        if (scopeToApply == AccessScope.CUSTOM && invitation.getAccessPayload() != null) {
            applyInvitationAccess(savedMember, invitation.getAccessPayload());
        }

        invitation.setStatus(InvitationStatus.ACCEPTED);
        homeInvitationRepository.save(invitation);
    }

    @Override
    @Transactional(readOnly = true)
    public Object getHomeMembers(UUID userId, UUID homeId) {
        HomeMember caller = homeMemberRepository.findByHomeIdAndUserId(homeId, userId)
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED));
        if (caller.getStatus() != MemberStatus.ACTIVE) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        boolean isCallerOwner = caller.getRole() == HomeRole.OWNER;
        boolean isCallerCustom = !isCallerOwner && caller.getAccessScope() == AccessScope.CUSTOM;

        List<HomeMember> members = homeMemberRepository.findByHomeIdWithUser(homeId);
        List<Map<String, Object>> result = new ArrayList<>();

        for (HomeMember m : members) {
            Map<String, Object> map = new HashMap<>();
            map.put("id", m.getUser().getId());
            map.put("fullName", m.getUser().getFullName() != null ? m.getUser().getFullName() : "");
            if (!isCallerCustom) {
                map.put("email", m.getUser().getEmail() != null ? m.getUser().getEmail() : "");
            }
            map.put("avatarUrl", m.getUser().getAvatarUrl() != null ? m.getUser().getAvatarUrl() : "");
            map.put("role", m.getRole().name());
            map.put("joinedAt", m.getJoinedAt());

            if (isCallerOwner) {
                AccessScope scope = m.getAccessScope() != null ? m.getAccessScope() : AccessScope.ALL;
                map.put("accessScope", scope.name());
                if (m.getRole() == HomeRole.OWNER || scope == AccessScope.ALL) {
                    map.put("accessRoomCount", 0L);
                    map.put("accessDeviceOverrideCount", 0L);
                    map.put("accessSummary", Map.of("scope", scope.name(), "allAccess", true));
                } else {
                    long roomCount = memberRoomAccessRepository.countByMemberId(m.getId());
                    long deviceCount = memberDeviceAccessRepository.countByMemberId(m.getId());
                    map.put("accessRoomCount", roomCount);
                    map.put("accessDeviceOverrideCount", deviceCount);
                    map.put("accessSummary", Map.of(
                            "scope", scope.name(),
                            "roomCount", roomCount,
                            "deviceCount", deviceCount,
                            "allAccess", false
                    ));
                }
            }
            result.add(map);
        }
        return result;
    }

    private void applyInvitationAccess(HomeMember member, String payloadJson) {
        try {
            var tree = objectMapper.readTree(payloadJson);
            UUID homeId = member.getHome().getId();
            Map<UUID, Room> liveRooms = roomRepository.findByHomeId(homeId).stream()
                    .collect(Collectors.toMap(Room::getId, r -> r));
            Map<UUID, Device> liveDevices = deviceRepository.findByHomeIdWithRoomAndNode(homeId).stream()
                    .filter(d -> !d.isDeleted())
                    .collect(Collectors.toMap(Device::getId, d -> d));

            int skippedRooms = 0;
            int skippedDevices = 0;

            if (tree.has("rooms") && tree.get("rooms").isArray()) {
                for (var rNode : tree.get("rooms")) {
                    UUID roomId = UUID.fromString(rNode.get("roomId").asText());
                    String levelStr = rNode.has("level") ? rNode.get("level").asText() : rNode.get("accessLevel").asText();
                    AccessLevel level = AccessLevel.valueOf(levelStr);
                    Room r = liveRooms.get(roomId);
                    if (r != null) {
                        memberRoomAccessRepository.save(MemberRoomAccess.builder()
                                .member(member)
                                .room(r)
                                .accessLevel(level)
                                .build());
                    } else {
                        skippedRooms++;
                    }
                }
            }

            if (tree.has("devices") && tree.get("devices").isArray()) {
                for (var dNode : tree.get("devices")) {
                    UUID deviceId = UUID.fromString(dNode.get("deviceId").asText());
                    String levelStr = dNode.has("level") ? dNode.get("level").asText() : dNode.get("accessLevel").asText();
                    AccessLevel level = AccessLevel.valueOf(levelStr);
                    Device d = liveDevices.get(deviceId);
                    if (d != null) {
                        memberDeviceAccessRepository.save(MemberDeviceAccess.builder()
                                .member(member)
                                .device(d)
                                .accessLevel(level)
                                .build());
                    } else {
                        skippedDevices++;
                    }
                }
            }

            if (skippedRooms > 0 || skippedDevices > 0) {
                log.warn("Join home: skipped {} room(s) and {} device(s) that no longer exist in home {}",
                        skippedRooms, skippedDevices, homeId);
            }
        } catch (Exception ex) {
            log.warn("Failed to apply invitation access: {}", ex.getMessage());
        }
    }



    @Override

    @Transactional(readOnly = true)

    public Object getHomeRooms(UUID userId, UUID homeId) {

        homeMemberRepository.findByHomeIdAndUserId(homeId, userId)

                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED));



        List<Room> rooms = roomRepository.findByHomeId(homeId);

        List<Map<String, Object>> result = new ArrayList<>();

        for (Room r : rooms) {

            result.add(Map.of(

                    "id", r.getId(),

                    "name", r.getName()

            ));

        }

        return result;

    }



    @Override

    @Transactional

    public void createHomeRoom(UUID userId, UUID homeId, String roomName, String icon, java.math.BigDecimal layoutX, java.math.BigDecimal layoutY) {

        homeMemberRepository.findByHomeIdAndUserId(homeId, userId)

                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED));

        

        Home home = homeRepository.findById(homeId).orElseThrow();

        Room room = Room.builder()

                .home(home)

                .name(roomName)

                .icon(icon)

                .layoutX(layoutX)

                .layoutY(layoutY)

                .build();

        roomRepository.save(room);

    }



    @Override

    @Transactional(readOnly = true)

    public Object getUserHomes(UUID userId) {

        List<HomeMember> memberships = homeMemberRepository.findByUserIdWithHomeOrderByJoinedAtDesc(userId);

        List<Map<String, Object>> result = new ArrayList<>();

        for (HomeMember m : memberships) {

            Map<String, Object> homeItem = new HashMap<>();
            homeItem.put("homeId", m.getHome().getId());
            homeItem.put("homeName", m.getHome().getName());
            homeItem.put("role", m.getRole().name());
            homeItem.put("accessScope", m.getAccessScope() != null ? m.getAccessScope().name() : "ALL");
            result.add(homeItem);

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

        if (ownerId.equals(memberId)) {
            throw new AppException(ErrorCode.CANNOT_CHANGE_OWNER_ROLE);
        }

        HomeMember target = homeMemberRepository.findByHomeIdAndUserId(homeId, memberId)
                .orElseThrow(() -> new AppException(ErrorCode.MEMBER_NOT_FOUND));

        if (target.getRole() == HomeRole.OWNER) {
            throw new AppException(ErrorCode.CANNOT_CHANGE_OWNER_ROLE);
        }

        target.setRole(newRole);
        if (newRole == HomeRole.OWNER) {
            target.setAccessScope(AccessScope.ALL);
            memberRoomAccessRepository.deleteByMemberId(target.getId());
            memberDeviceAccessRepository.deleteByMemberId(target.getId());
            memberRoomAccessRepository.flush();
            memberDeviceAccessRepository.flush();
        }
        homeMemberRepository.save(target);
        if (applicationEventPublisher != null) {
            applicationEventPublisher.publishEvent(new MemberAccessChangedEvent(homeId, memberId));
        }
    }

    @Override
    @Transactional
    public void removeMember(UUID ownerId, UUID homeId, UUID memberId) {
        HomeMember owner = homeMemberRepository.findByHomeIdAndUserId(homeId, ownerId)
                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED));
        if (owner.getRole() != HomeRole.OWNER) {
            throw new AppException(ErrorCode.UNAUTHORIZED);
        }

        if (ownerId.equals(memberId)) {
            throw new AppException(ErrorCode.CANNOT_REMOVE_SELF);
        }

        HomeMember target = homeMemberRepository.findByHomeIdAndUserId(homeId, memberId)
                .orElseThrow(() -> new AppException(ErrorCode.MEMBER_NOT_FOUND));

        if (target.getRole() == HomeRole.OWNER) {
            throw new AppException(ErrorCode.CANNOT_REMOVE_OWNER);
        }

        memberRoomAccessRepository.deleteByMemberId(target.getId());
        memberDeviceAccessRepository.deleteByMemberId(target.getId());
        memberRoomAccessRepository.flush();
        memberDeviceAccessRepository.flush();
        homeMemberRepository.delete(target);
        if (applicationEventPublisher != null) {
            applicationEventPublisher.publishEvent(new MemberAccessChangedEvent(homeId, memberId));
        }
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



    @Override

    @Transactional

    public Map<String, Object> updateHome(UUID userId, UUID homeId, String newName) {

        HomeMember member = homeMemberRepository.findByHomeIdAndUserId(homeId, userId)

                .orElseThrow(() -> new AppException(ErrorCode.UNAUTHORIZED));

        if (member.getRole() != HomeRole.OWNER) {

            throw new AppException(ErrorCode.UNAUTHORIZED);

        }



        Home home = homeRepository.findById(homeId)

                .orElseThrow(() -> new AppException(ErrorCode.HOME_NOT_FOUND));



        home.setName(newName.trim());

        Home savedHome = homeRepository.save(home);



        return Map.of(

                "homeId", savedHome.getId(),

                "homeName", savedHome.getName(),

                "role", member.getRole().name()

        );

    }

}

