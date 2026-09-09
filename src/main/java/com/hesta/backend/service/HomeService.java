package com.hesta.backend.service;

import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.enums.HomeRole;

import java.util.Map;
import java.util.UUID;

public interface HomeService {
    Map<String, Object> generateInvitation(UUID inviterId, UUID homeId, String email);
    void joinHome(UUID userId, String codeOrToken);
    Object getHomeMembers(UUID userId, UUID homeId);
    Object getUserHomes(UUID userId);
    void updateMemberRole(UUID ownerId, UUID homeId, UUID memberId, HomeRole newRole);
    void removeMember(UUID ownerId, UUID homeId, UUID memberId);
    Map<String, Object> createHome(UUID userId, String homeName);
}
