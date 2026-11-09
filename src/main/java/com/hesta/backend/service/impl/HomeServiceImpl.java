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

    private final com.hesta.backend.repository.RoomRepository roomRepository;

    private final MemberRoomAccessRepository memberRoomAccessRepository;

    private final MemberDeviceAccessRepository memberDeviceAccessRepository;

    private final DeviceRepository deviceRepository;

    private final com.fasterxml.jackson.databind.ObjectMapper objectMapper;



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

        String targetEmail = (request != null && request.getEmail() != null && !request.getEmail().trim().isEmpty())
                ? request.getEmail().trim()
                : (email != null ? email.trim() : null);

        AccessScope scope = (request != null && request.getAccessScope() != null)
                ? request.getAccessScope()
                : AccessScope.ALL;

        String accessPayload = null;
        if (scope == AccessScope.CUSTOM && request != null) {
            try {
                Map<UUID, Room> homeRoomsById = roomRepository.findByHomeId(homeId).stream()
                        .collect(Collectors.toMap(Room::getId, r -> r));
                Map<UUID, Device> homeDevicesById = deviceRepository.findByHomeIdWithRoomAndNode(homeId).stream()
                        .filter(d -> !d.isDeleted())
                        .collect(Collectors.toMap(Device::getId, d -> d));

                if (request.getRooms() != null) {
                    for (var r : request.getRooms()) {
                        if (r.getRoomId() != null && !homeRoomsById.containsKey(r.getRoomId())) {
                            throw new AppException(ErrorCode.INVALID_ACCESS_PAYLOAD);
                        }
                    }
                }
                if (request.getDevices() != null) {
                    for (var d : request.getDevices()) {
                        if (d.getDeviceId() != null && !homeDevicesById.containsKey(d.getDeviceId())) {
                            throw new AppException(ErrorCode.INVALID_ACCESS_PAYLOAD);
                        }
                    }
                }
                Map<String, Object> payloadMap = new HashMap<>();
                payloadMap.put("rooms", request.getRooms() != null ? request.getRooms() : List.of());
                payloadMap.put("devices", request.getDevices() != null ? request.getDevices() : List.of());
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

            throw new RuntimeException("Bạn đã là thành viên của nhà này");

        }



        HomeMember member = HomeMember.builder()

                .home(invitation.getHome())

                .user(user)

                .role(HomeRole.MEMBER)

                .invitedBy(invitation.getInviter())

                .accessScope(invitation.getAccessScope() != null ? invitation.getAccessScope() : AccessScope.ALL)

                .build();

        HomeMember savedMember = homeMemberRepository.save(member);

        if (savedMember.getAccessScope() == AccessScope.CUSTOM && invitation.getAccessPayload() != null) {
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
                    map.put("accessSummary", Map.of("scope", scope.name(), "allAccess", true));
                } else {
                    long roomCount = memberRoomAccessRepository.countByMemberId(m.getId());
                    long deviceCount = memberDeviceAccessRepository.countByMemberId(m.getId());
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

            if (tree.has("rooms") && tree.get("rooms").isArray()) {
                for (var rNode : tree.get("rooms")) {
                    UUID roomId = UUID.fromString(rNode.get("roomId").asText());
                    AccessLevel level = AccessLevel.valueOf(rNode.get("accessLevel").asText());
                    Room r = liveRooms.get(roomId);
                    if (r != null) {
                        memberRoomAccessRepository.save(MemberRoomAccess.builder()
                                .member(member)
                                .room(r)
                                .accessLevel(level)
                                .build());
                    }
                }
            }

            if (tree.has("devices") && tree.get("devices").isArray()) {
                for (var dNode : tree.get("devices")) {
                    UUID deviceId = UUID.fromString(dNode.get("deviceId").asText());
                    AccessLevel level = AccessLevel.valueOf(dNode.get("accessLevel").asText());
                    Device d = liveDevices.get(deviceId);
                    if (d != null) {
                        memberDeviceAccessRepository.save(MemberDeviceAccess.builder()
                                .member(member)
                                .device(d)
                                .accessLevel(level)
                                .build());
                    }
                }
            }
        } catch (Exception ex) {
            // Skip invalid IDs without failing join
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

        if (ownerId.equals(memberId)) {
            throw new AppException(ErrorCode.CANNOT_CHANGE_OWNER_ROLE);
        }

        HomeMember target = homeMemberRepository.findByHomeIdAndUserId(homeId, memberId)
                .orElseThrow(() -> new AppException(ErrorCode.MEMBER_NOT_FOUND));

        if (target.getRole() == HomeRole.OWNER) {
            throw new AppException(ErrorCode.CANNOT_CHANGE_OWNER_ROLE);
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

        if (ownerId.equals(memberId)) {
            throw new AppException(ErrorCode.CANNOT_REMOVE_SELF);
        }

        HomeMember target = homeMemberRepository.findByHomeIdAndUserId(homeId, memberId)
                .orElseThrow(() -> new AppException(ErrorCode.MEMBER_NOT_FOUND));

        if (target.getRole() == HomeRole.OWNER) {
            throw new AppException(ErrorCode.CANNOT_REMOVE_OWNER);
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

