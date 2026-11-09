package com.hesta.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.access.MemberAccessDetailResponse;
import com.hesta.backend.dto.access.MemberDeviceAccessDto;
import com.hesta.backend.dto.access.MemberRoomAccessDto;
import com.hesta.backend.dto.access.UpdateMemberAccessRequest;
import com.hesta.backend.enums.AccessLevel;
import com.hesta.backend.enums.AccessScope;
import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.HomeMemberAccessService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class HomeMemberAccessControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private HomeMemberAccessService memberAccessService;

    private final UUID homeId = UUID.randomUUID();
    private final UUID ownerUserId = UUID.randomUUID();
    private final UUID targetUserId = UUID.randomUUID();
    private final UUID otherUserId = UUID.randomUUID();
    private final UUID memberId = UUID.randomUUID();
    private final UUID roomId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();

    private UsernamePasswordAuthenticationToken ownerAuth;
    private UsernamePasswordAuthenticationToken memberAuth;

    @BeforeEach
    void setUp() {
        CustomUserDetails ownerDetails = new CustomUserDetails(
                ownerUserId,
                "owner@hesta.vn",
                "hashedpassword",
                AccountStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        ownerAuth = new UsernamePasswordAuthenticationToken(ownerDetails, null, ownerDetails.getAuthorities());

        CustomUserDetails memberDetails = new CustomUserDetails(
                targetUserId,
                "member@hesta.vn",
                "hashedpassword",
                AccountStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        memberAuth = new UsernamePasswordAuthenticationToken(memberDetails, null, memberDetails.getAuthorities());
    }

    @Test
    @DisplayName("GET - 200 with MemberAccessDetailResponse for OWNER caller")
    void testGetMemberAccess_Owner_Returns200() throws Exception {
        MemberAccessDetailResponse mockResponse = MemberAccessDetailResponse.builder()
                .memberUserId(targetUserId)
                .memberId(memberId)
                .fullName("Test Member")
                .role("MEMBER")
                .scope(AccessScope.CUSTOM)
                .version("mock-sha256-version")
                .readOnly(false)
                .rooms(List.of(MemberRoomAccessDto.builder().roomId(roomId).level(AccessLevel.CONTROL).build()))
                .devices(List.of(MemberDeviceAccessDto.builder().deviceId(deviceId).effectiveLevel(AccessLevel.CONTROL).build()))
                .build();

        when(memberAccessService.getMemberAccess(eq(ownerUserId), eq(homeId), eq(targetUserId)))
                .thenReturn(mockResponse);

        mockMvc.perform(get("/api/v1/homes/{homeId}/members/{userId}/access", homeId, targetUserId)
                        .with(authentication(ownerAuth))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.memberUserId").value(targetUserId.toString()))
                .andExpect(jsonPath("$.result.scope").value("CUSTOM"))
                .andExpect(jsonPath("$.result.version").value("mock-sha256-version"));
    }

    @Test
    @DisplayName("GET - Self view returns 200 with readOnly=true")
    void testGetMemberAccess_SelfView_Returns200() throws Exception {
        MemberAccessDetailResponse mockResponse = MemberAccessDetailResponse.builder()
                .memberUserId(targetUserId)
                .memberId(memberId)
                .fullName("Test Member")
                .role("MEMBER")
                .scope(AccessScope.CUSTOM)
                .version("mock-sha256-version")
                .readOnly(true)
                .rooms(List.of(MemberRoomAccessDto.builder().roomId(roomId).level(AccessLevel.VIEW).build()))
                .devices(List.of(MemberDeviceAccessDto.builder().deviceId(deviceId).effectiveLevel(AccessLevel.VIEW).build()))
                .build();

        when(memberAccessService.getMemberAccess(eq(targetUserId), eq(homeId), eq(targetUserId)))
                .thenReturn(mockResponse);

        mockMvc.perform(get("/api/v1/homes/{homeId}/members/{userId}/access", homeId, targetUserId)
                        .with(authentication(memberAuth))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.readOnly").value(true));
    }

    @Test
    @DisplayName("GET - Non-owner reading someone else returns 403 Forbidden")
    void testGetMemberAccess_NonOwnerReadingOther_Returns403() throws Exception {
        when(memberAccessService.getMemberAccess(eq(targetUserId), eq(homeId), eq(otherUserId)))
                .thenThrow(new AppException(ErrorCode.UNAUTHORIZED));

        mockMvc.perform(get("/api/v1/homes/{homeId}/members/{userId}/access", homeId, otherUserId)
                        .with(authentication(memberAuth))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("GET - Target not in home returns 404 Not Found")
    void testGetMemberAccess_NotFound_Returns404() throws Exception {
        when(memberAccessService.getMemberAccess(eq(ownerUserId), eq(homeId), eq(otherUserId)))
                .thenThrow(new AppException(ErrorCode.MEMBER_NOT_FOUND));

        mockMvc.perform(get("/api/v1/homes/{homeId}/members/{userId}/access", homeId, otherUserId)
                        .with(authentication(ownerAuth))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName("GET - Unauthenticated caller returns 401 Unauthorized")
    void testGetMemberAccess_Unauthenticated() throws Exception {
        mockMvc.perform(get("/api/v1/homes/{homeId}/members/{userId}/access", homeId, targetUserId)
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PUT - 200 on valid update")
    void testUpdateMemberAccess_Success_Returns200() throws Exception {
        UpdateMemberAccessRequest request = UpdateMemberAccessRequest.builder()
                .scope(AccessScope.CUSTOM)
                .version("mock-sha256-version")
                .rooms(List.of(MemberRoomAccessDto.builder().roomId(roomId).level(AccessLevel.CONTROL).build()))
                .devices(List.of(MemberDeviceAccessDto.builder().deviceId(deviceId).level(AccessLevel.CONTROL).build()))
                .build();

        MemberAccessDetailResponse mockResponse = MemberAccessDetailResponse.builder()
                .memberUserId(targetUserId)
                .memberId(memberId)
                .role("MEMBER")
                .scope(AccessScope.CUSTOM)
                .version("new-mock-sha256-version")
                .build();

        when(memberAccessService.updateMemberAccess(eq(ownerUserId), eq(homeId), eq(targetUserId), any()))
                .thenReturn(mockResponse);

        mockMvc.perform(put("/api/v1/homes/{homeId}/members/{userId}/access", homeId, targetUserId)
                        .with(authentication(ownerAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.version").value("new-mock-sha256-version"));
    }

    @Test
    @DisplayName("PUT - Flag off with scope CUSTOM returns 409 FEATURE_DISABLED")
    void testUpdateMemberAccess_FlagOff_Returns409() throws Exception {
        UpdateMemberAccessRequest request = UpdateMemberAccessRequest.builder().scope(AccessScope.CUSTOM).build();
        when(memberAccessService.updateMemberAccess(eq(ownerUserId), eq(homeId), eq(targetUserId), any()))
                .thenThrow(new AppException(ErrorCode.FEATURE_DISABLED, "Chưa bật quyền tùy chỉnh"));

        mockMvc.perform(put("/api/v1/homes/{homeId}/members/{userId}/access", homeId, targetUserId)
                        .with(authentication(ownerAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.message").value("Chưa bật quyền tùy chỉnh"));
    }

    @Test
    @DisplayName("PUT - Stale version returns 409 STALE_ACCESS_VERSION")
    void testUpdateMemberAccess_StaleVersion_Returns409() throws Exception {
        UpdateMemberAccessRequest request = UpdateMemberAccessRequest.builder()
                .scope(AccessScope.CUSTOM)
                .version("stale-version")
                .build();
        when(memberAccessService.updateMemberAccess(eq(ownerUserId), eq(homeId), eq(targetUserId), any()))
                .thenThrow(new AppException(ErrorCode.STALE_ACCESS_VERSION));

        mockMvc.perform(put("/api/v1/homes/{homeId}/members/{userId}/access", homeId, targetUserId)
                        .with(authentication(ownerAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("PUT - Target OWNER or self returns 409 CANNOT_MODIFY_OWNER_ACCESS")
    void testUpdateMemberAccess_TargetOwnerOrSelf_Returns409() throws Exception {
        UpdateMemberAccessRequest request = UpdateMemberAccessRequest.builder().scope(AccessScope.CUSTOM).build();
        when(memberAccessService.updateMemberAccess(eq(ownerUserId), eq(homeId), eq(ownerUserId), any()))
                .thenThrow(new AppException(ErrorCode.CANNOT_MODIFY_OWNER_ACCESS));

        mockMvc.perform(put("/api/v1/homes/{homeId}/members/{userId}/access", homeId, ownerUserId)
                        .with(authentication(ownerAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName("PUT - Non-owner caller returns 403 Forbidden")
    void testUpdateMemberAccess_NonOwner_Returns403() throws Exception {
        UpdateMemberAccessRequest request = UpdateMemberAccessRequest.builder().scope(AccessScope.CUSTOM).build();
        when(memberAccessService.updateMemberAccess(eq(targetUserId), eq(homeId), eq(otherUserId), any()))
                .thenThrow(new AppException(ErrorCode.UNAUTHORIZED));

        mockMvc.perform(put("/api/v1/homes/{homeId}/members/{userId}/access", homeId, otherUserId)
                        .with(authentication(memberAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("PUT - Invalid payload (duplicates, foreign IDs) returns 400 Bad Request")
    void testUpdateMemberAccess_InvalidPayload_Returns400() throws Exception {
        UpdateMemberAccessRequest request = UpdateMemberAccessRequest.builder().scope(AccessScope.CUSTOM).build();
        when(memberAccessService.updateMemberAccess(eq(ownerUserId), eq(homeId), eq(targetUserId), any()))
                .thenThrow(new AppException(ErrorCode.INVALID_ACCESS_PAYLOAD, "Dữ liệu phân quyền không hợp lệ"));

        mockMvc.perform(put("/api/v1/homes/{homeId}/members/{userId}/access", homeId, targetUserId)
                        .with(authentication(ownerAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }
}
