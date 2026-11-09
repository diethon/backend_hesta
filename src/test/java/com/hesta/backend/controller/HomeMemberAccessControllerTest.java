package com.hesta.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.access.MemberAccessDetailResponse;
import com.hesta.backend.dto.access.MemberDeviceAccessDto;
import com.hesta.backend.dto.access.MemberRoomAccessDto;
import com.hesta.backend.dto.access.UpdateMemberAccessRequest;
import com.hesta.backend.enums.AccessLevel;
import com.hesta.backend.enums.AccessScope;
import com.hesta.backend.enums.AccountStatus;
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

import java.time.Instant;
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
    private final UUID memberId = UUID.randomUUID();
    private final UUID roomId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();

    private UsernamePasswordAuthenticationToken auth;

    @BeforeEach
    void setUp() {
        CustomUserDetails userDetails = new CustomUserDetails(
                ownerUserId,
                "owner@hesta.vn",
                "hashedpassword",
                AccountStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        auth = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
    }

    @Test
    @DisplayName("GET /api/v1/homes/{homeId}/members/{memberUserId}/access returns 200 with MemberAccessDetailResponse")
    void testGetMemberAccessEndpoint() throws Exception {
        MemberAccessDetailResponse mockResponse = MemberAccessDetailResponse.builder()
                .memberId(memberId)
                .userId(targetUserId)
                .fullName("Test Member")
                .role("MEMBER")
                .accessScope(AccessScope.CUSTOM)
                .rooms(List.of(MemberRoomAccessDto.builder()
                        .roomId(roomId)
                        .roomName("Phòng khách")
                        .accessLevel(AccessLevel.CONTROL)
                        .build()))
                .devices(List.of(MemberDeviceAccessDto.builder()
                        .deviceId(deviceId)
                        .deviceName("Đèn trần")
                        .roomId(roomId)
                        .roomName("Phòng khách")
                        .accessLevel(AccessLevel.CONTROL)
                        .build()))
                .version(Instant.now().toEpochMilli())
                .build();

        when(memberAccessService.getMemberAccess(eq(ownerUserId), eq(homeId), eq(targetUserId)))
                .thenReturn(mockResponse);

        mockMvc.perform(get("/api/v1/homes/{homeId}/members/{memberUserId}/access", homeId, targetUserId)
                        .with(authentication(auth))
                        .accept(MediaType.APPLICATION_JSON))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.memberId").value(memberId.toString()))
                .andExpect(jsonPath("$.result.accessScope").value("CUSTOM"))
                .andExpect(jsonPath("$.result.rooms[0].roomId").value(roomId.toString()))
                .andExpect(jsonPath("$.result.devices[0].deviceId").value(deviceId.toString()));
    }

    @Test
    @DisplayName("PUT /api/v1/homes/{homeId}/members/{memberUserId}/access returns 200 with updated response")
    void testUpdateMemberAccessEndpoint() throws Exception {
        UpdateMemberAccessRequest request = UpdateMemberAccessRequest.builder()
                .accessScope(AccessScope.CUSTOM)
                .rooms(List.of(MemberRoomAccessDto.builder().roomId(roomId).accessLevel(AccessLevel.CONTROL).build()))
                .devices(List.of(MemberDeviceAccessDto.builder().deviceId(deviceId).accessLevel(AccessLevel.CONTROL).build()))
                .build();

        MemberAccessDetailResponse mockResponse = MemberAccessDetailResponse.builder()
                .memberId(memberId)
                .userId(targetUserId)
                .fullName("Test Member")
                .role("MEMBER")
                .accessScope(AccessScope.CUSTOM)
                .rooms(List.of(MemberRoomAccessDto.builder()
                        .roomId(roomId)
                        .roomName("Phòng khách")
                        .accessLevel(AccessLevel.CONTROL)
                        .build()))
                .devices(List.of(MemberDeviceAccessDto.builder()
                        .deviceId(deviceId)
                        .deviceName("Đèn trần")
                        .roomId(roomId)
                        .roomName("Phòng khách")
                        .accessLevel(AccessLevel.CONTROL)
                        .build()))
                .version(Instant.now().toEpochMilli())
                .build();

        when(memberAccessService.updateMemberAccess(eq(ownerUserId), eq(homeId), eq(targetUserId), any()))
                .thenReturn(mockResponse);

        mockMvc.perform(put("/api/v1/homes/{homeId}/members/{memberUserId}/access", homeId, targetUserId)
                        .with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("Cập nhật phân quyền thành công"))
                .andExpect(jsonPath("$.result.accessScope").value("CUSTOM"));
    }
}
