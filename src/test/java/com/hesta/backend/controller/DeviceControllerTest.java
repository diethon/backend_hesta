package com.hesta.backend.controller;

import com.hesta.backend.dto.response.CapabilityGroupResponse;
import com.hesta.backend.dto.response.DeviceCapabilityInfoResponse;
import com.hesta.backend.dto.response.HomeCapabilitiesResponse;
import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.DeviceService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class DeviceControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DeviceService deviceService;

    private UUID userId;
    private UUID homeId;
    private UUID deviceId;
    private UsernamePasswordAuthenticationToken userAuth;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        homeId = UUID.randomUUID();
        deviceId = UUID.randomUUID();

        CustomUserDetails user = new CustomUserDetails(
                userId, "user@test.com", "pass", AccountStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        userAuth = new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
    }

    @Test
    @DisplayName("GET /api/v1/homes/{homeId}/device-capabilities returns home capabilities")
    void getHomeCapabilities_returnsSuccess() throws Exception {
        DeviceCapabilityInfoResponse dev = DeviceCapabilityInfoResponse.builder()
                .id(deviceId)
                .homeId(homeId)
                .name("Living Light")
                .deviceType("LIGHT")
                .status("ONLINE")
                .capabilities(Map.of("POWER", List.of("TURN_ON", "TURN_OFF", "TOGGLE")))
                .supportedActions(List.of("TURN_ON", "TURN_OFF", "TOGGLE"))
                .supportedAttributes(List.of("power"))
                .sensor(false)
                .actionable(true)
                .build();

        CapabilityGroupResponse cap = CapabilityGroupResponse.builder()
                .code("POWER")
                .label("Bật / Tắt nguồn")
                .icon("power")
                .actions(List.of("TURN_ON", "TURN_OFF", "TOGGLE"))
                .deviceIds(List.of(deviceId))
                .deviceCount(1)
                .build();

        HomeCapabilitiesResponse response = HomeCapabilitiesResponse.builder()
                .homeId(homeId)
                .devices(List.of(dev))
                .capabilities(List.of(cap))
                .actionTypes(List.of("TURN_ON", "TURN_OFF", "TOGGLE"))
                .sensorAttributes(List.of())
                .build();

        when(deviceService.getHomeCapabilities(userId, homeId)).thenReturn(response);

        mockMvc.perform(get("/api/v1/homes/{homeId}/device-capabilities", homeId)
                        .with(authentication(userAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.homeId").value(homeId.toString()))
                .andExpect(jsonPath("$.result.devices[0].name").value("Living Light"))
                .andExpect(jsonPath("$.result.capabilities[0].code").value("POWER"))
                .andExpect(jsonPath("$.result.actionTypes[0]").value("TURN_ON"));
    }

    @Test
    @DisplayName("GET /api/v1/devices/{deviceId}/capabilities returns device capability detail")
    void getDeviceCapabilities_returnsSuccess() throws Exception {
        DeviceCapabilityInfoResponse dev = DeviceCapabilityInfoResponse.builder()
                .id(deviceId)
                .homeId(homeId)
                .name("Living Light")
                .deviceType("LIGHT")
                .status("ONLINE")
                .capabilities(Map.of("POWER", List.of("TURN_ON", "TURN_OFF", "TOGGLE")))
                .supportedActions(List.of("TURN_ON", "TURN_OFF", "TOGGLE"))
                .supportedAttributes(List.of("power"))
                .sensor(false)
                .actionable(true)
                .build();

        when(deviceService.getDeviceCapabilities(userId, deviceId)).thenReturn(dev);

        mockMvc.perform(get("/api/v1/devices/{deviceId}/capabilities", deviceId)
                        .with(authentication(userAuth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.id").value(deviceId.toString()))
                .andExpect(jsonPath("$.result.deviceType").value("LIGHT"));
    }
}
