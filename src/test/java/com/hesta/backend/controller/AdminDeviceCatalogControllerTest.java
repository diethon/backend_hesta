package com.hesta.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.request.DeviceBatchGenerateQrRequest;
import com.hesta.backend.dto.request.DeviceCatalogRequest;
import com.hesta.backend.dto.response.*;
import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.DeviceCatalogService;
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
import java.util.Map;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class AdminDeviceCatalogControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DeviceCatalogService catalogService;

    private UUID adminId;
    private UsernamePasswordAuthenticationToken adminAuth;
    private UsernamePasswordAuthenticationToken userAuth;

    @BeforeEach
    void setUp() {
        adminId = UUID.randomUUID();
        CustomUserDetails adminDetails = new CustomUserDetails(
                adminId,
                "admin@hesta.vn",
                "",
                AccountStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority("ROLE_ADMIN"))
        );
        adminAuth = new UsernamePasswordAuthenticationToken(adminDetails, null, adminDetails.getAuthorities());

        CustomUserDetails normalDetails = new CustomUserDetails(
                UUID.randomUUID(),
                "user@hesta.vn",
                "",
                AccountStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        userAuth = new UsernamePasswordAuthenticationToken(normalDetails, null, normalDetails.getAuthorities());
    }

    @Test
    @DisplayName("Admin can fetch device catalogs list")
    void getCatalogs_AsAdmin_Success() throws Exception {
        DeviceCatalogResponse item = DeviceCatalogResponse.builder()
                .id(UUID.randomUUID())
                .name("Đèn LED RGB Hesta")
                .deviceType("LED_RGB")
                .modelCode("HESTA-RGB-01")
                .serialPrefix("HESTA-RGB-")
                .isActive(true)
                .totalDeviceCount(10)
                .build();

        when(catalogService.getCatalogs(any(), any())).thenReturn(List.of(item));

        mockMvc.perform(get("/api/v1/admin/device-catalogs")
                        .with(authentication(adminAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result[0].name").value("Đèn LED RGB Hesta"))
                .andExpect(jsonPath("$.result[0].modelCode").value("HESTA-RGB-01"));
    }

    @Test
    @DisplayName("Admin can fetch device stats overview")
    void getStats_AsAdmin_Success() throws Exception {
        DeviceAdminStatsResponse stats = DeviceAdminStatsResponse.builder()
                .totalCatalogs(6)
                .totalDevices(25)
                .unclaimedDevices(15)
                .claimedDevices(10)
                .typeBreakdown(Map.of("LED_RGB", 10L, "LIGHT", 15L))
                .build();

        when(catalogService.getDeviceAdminStats()).thenReturn(stats);

        mockMvc.perform(get("/api/v1/admin/device-catalogs/stats")
                        .with(authentication(adminAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.totalCatalogs").value(6))
                .andExpect(jsonPath("$.result.unclaimedDevices").value(15));
    }

    @Test
    @DisplayName("Admin can batch generate QR codes for a catalog")
    void batchGenerateQr_AsAdmin_Success() throws Exception {
        UUID catalogId = UUID.randomUUID();
        DeviceBatchGenerateQrRequest req = DeviceBatchGenerateQrRequest.builder()
                .catalogId(catalogId)
                .quantity(2)
                .build();

        AdminDeviceCreatedResponse dev1 = AdminDeviceCreatedResponse.builder()
                .device(DeviceResponse.builder().name("Đèn LED #1").serialNumber("HESTA-RGB-0001").build())
                .qr(QrInfoResponse.builder().payload("syna://device/register?token=abc").token("abc").build())
                .build();

        DeviceBatchGenerateQrResponse resp = DeviceBatchGenerateQrResponse.builder()
                .generatedCount(2)
                .items(List.of(dev1))
                .build();

        when(catalogService.batchGenerateQr(eq(adminId), any())).thenReturn(resp);

        mockMvc.perform(post("/api/v1/admin/device-catalogs/batch-generate")
                        .with(authentication(adminAuth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.generatedCount").value(2));
    }

    @Test
    @DisplayName("Normal user cannot access device catalog endpoints")
    void getCatalogs_AsNormalUser_Forbidden() throws Exception {
        mockMvc.perform(get("/api/v1/admin/device-catalogs")
                        .with(authentication(userAuth)))
                .andExpect(status().isForbidden());
    }
}
