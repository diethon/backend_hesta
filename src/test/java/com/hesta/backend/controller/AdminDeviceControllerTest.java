package com.hesta.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.request.AdminCreateDeviceRequest;
import com.hesta.backend.dto.request.DeviceClaimRequest;
import com.hesta.backend.dto.response.AdminDeviceCreatedResponse;
import com.hesta.backend.dto.response.DevicePublicPreviewResponse;
import com.hesta.backend.dto.response.DeviceResponse;
import com.hesta.backend.dto.response.QrInfoResponse;
import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.enums.DeviceStatus;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.DeviceOnboardingService;
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

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminDeviceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DeviceOnboardingService onboardingService;

    private UUID adminId;
    private UUID userId;
    private UUID deviceId;
    private UsernamePasswordAuthenticationToken adminAuth;
    private UsernamePasswordAuthenticationToken userAuth;

    @BeforeEach
    void setUp() {
        adminId = UUID.randomUUID();
        userId = UUID.randomUUID();
        deviceId = UUID.randomUUID();

        CustomUserDetails adminPrincipal = new CustomUserDetails(
                adminId,
                "admin@hesta.com",
                "",
                AccountStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN")));
        adminAuth = new UsernamePasswordAuthenticationToken(adminPrincipal, null, adminPrincipal.getAuthorities());

        CustomUserDetails userPrincipal = new CustomUserDetails(
                userId,
                "user@hesta.com",
                "",
                AccountStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        userAuth = new UsernamePasswordAuthenticationToken(userPrincipal, null, userPrincipal.getAuthorities());
    }

    @Test
    @DisplayName("Admin create device returns 201 and QR details")
    void adminCreateDevice_AsAdmin_ReturnsCreated() throws Exception {
        AdminCreateDeviceRequest request = AdminCreateDeviceRequest.builder()
                .name("SYNA RGB Light")
                .deviceType("LIGHT")
                .model("SYNA-RGB-001")
                .serialNumber("SYNA-RGB-001-0001")
                .build();

        AdminDeviceCreatedResponse response = AdminDeviceCreatedResponse.builder()
                .device(DeviceResponse.builder()
                        .id(deviceId)
                        .name("SYNA RGB Light")
                        .deviceType("LIGHT")
                        .model("SYNA-RGB-001")
                        .serialNumber("SYNA-RGB-001-0001")
                        .status(DeviceStatus.UNCLAIMED.name())
                        .build())
                .qr(QrInfoResponse.builder()
                        .payload("syna://device/register?token=sample123")
                        .token("sample123")
                        .expiresAt(OffsetDateTime.now().plusDays(7))
                        .singleUse(true)
                        .build())
                .build();

        when(onboardingService.adminCreateDevice(eq(adminId), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/admin/devices")
                        .with(authentication(adminAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.device.name").value("SYNA RGB Light"))
                .andExpect(jsonPath("$.result.device.status").value("UNCLAIMED"))
                .andExpect(jsonPath("$.result.qr.token").value("sample123"));
    }

    @Test
    @DisplayName("User create device returns 403 Forbidden")
    void adminCreateDevice_AsUser_ReturnsForbidden() throws Exception {
        AdminCreateDeviceRequest request = AdminCreateDeviceRequest.builder()
                .name("SYNA RGB Light")
                .deviceType("LIGHT")
                .serialNumber("SYNA-RGB-001-0001")
                .build();

        mockMvc.perform(post("/api/v1/admin/devices")
                        .with(authentication(userAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("Create device without authentication returns 401 Unauthorized")
    void adminCreateDevice_Unauthenticated_ReturnsUnauthorized() throws Exception {
        AdminCreateDeviceRequest request = AdminCreateDeviceRequest.builder()
                .name("SYNA RGB Light")
                .deviceType("LIGHT")
                .serialNumber("SYNA-RGB-001-0001")
                .build();

        mockMvc.perform(post("/api/v1/admin/devices")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("Public resolve QR token works without authentication")
    void resolveQrToken_Public_Success() throws Exception {
        DevicePublicPreviewResponse preview = DevicePublicPreviewResponse.builder()
                .deviceId(deviceId)
                .name("SYNA RGB Light")
                .deviceType("LIGHT")
                .model("SYNA-RGB-001")
                .serialNumber("SYNA-RGB-001-0001")
                .status("UNCLAIMED")
                .build();

        when(onboardingService.resolveQrToken("valid-token-abc")).thenReturn(preview);

        mockMvc.perform(get("/api/v1/devices/qr/valid-token-abc"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.name").value("SYNA RGB Light"))
                .andExpect(jsonPath("$.result.status").value("UNCLAIMED"));
    }

    @Test
    @DisplayName("User claim device returns 200 OK")
    void claimDevice_AsUser_Success() throws Exception {
        UUID homeId = UUID.randomUUID();
        UUID roomId = UUID.randomUUID();
        DeviceClaimRequest request = DeviceClaimRequest.builder()
                .token("valid-token-abc")
                .homeId(homeId)
                .roomId(roomId)
                .name("Living Room RGB Light")
                .nodeCode("esp32-001")
                .build();

        DeviceResponse response = DeviceResponse.builder()
                .id(deviceId)
                .name("Living Room RGB Light")
                .roomId(roomId)
                .homeId(homeId)
                .status(DeviceStatus.ONLINE.name())
                .build();

        when(onboardingService.claimDevice(eq(userId), eq(deviceId), any())).thenReturn(response);

        mockMvc.perform(post("/api/v1/devices/{deviceId}/claim", deviceId)
                        .with(authentication(userAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.name").value("Living Room RGB Light"))
                .andExpect(jsonPath("$.result.status").value("ONLINE"));
    }
}
