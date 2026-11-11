package com.hesta.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.config.SecurityConfig;
import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.request.DeviceUpdateRequest;
import com.hesta.backend.dto.response.*;
import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.security.*;
import com.hesta.backend.service.*;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = DeviceController.class,
        excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, CustomAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class})
class DeviceControllerTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private DeviceService deviceService;

    @MockitoBean(name = "mqttDeviceCommandServiceImpl")
    private DeviceCommandService deviceCommandService;

    @MockitoBean
    private ManualControlService manualControlService;

    @MockitoBean
    private DeviceOnboardingService onboardingService;

    @MockitoBean
    private ManualOverrideService manualOverrideService;

    @MockitoBean
    private DeviceProfilingService deviceProfilingService;

    @MockitoBean
    private DeviceHealthService deviceHealthService;

    @MockitoBean(name = "deviceAccessValidator")
    private DeviceAccessValidator deviceAccessValidator;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    private final UUID userId = UUID.randomUUID();
    private final UUID homeId = UUID.randomUUID();
    private final UUID roomId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();

    private UsernamePasswordAuthenticationToken auth() {
        CustomUserDetails user = new CustomUserDetails(
                userId, "user@test.com", "pass", AccountStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        return new UsernamePasswordAuthenticationToken(user, null, user.getAuthorities());
    }

    @Test
    void getHomeDevices_authenticated_returnsList() throws Exception {
        DeviceResponse d = DeviceResponse.builder().id(deviceId).name("Fan").build();
        when(deviceService.getDevicesByHome(userId, homeId)).thenReturn(List.of(d));

        mvc.perform(get("/api/v1/homes/{homeId}/devices", homeId)
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result[0].name").value("Fan"));
    }

    @Test
    void updateDevice_validBody_returnsUpdatedDevice() throws Exception {
        DeviceUpdateRequest req = new DeviceUpdateRequest();
        req.setName("New Name");
        DeviceResponse res = DeviceResponse.builder().id(deviceId).name("New Name").build();
        when(deviceService.updateDeviceConfig(eq(userId), eq(deviceId), any(DeviceUpdateRequest.class))).thenReturn(res);

        mvc.perform(put("/api/v1/devices/{deviceId}", deviceId)
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.name").value("New Name"));
    }

    @Test
    void sendCommand_validAction_returnsCommandResult() throws Exception {
        CommandResult result = CommandResult.builder()
                .commandId(UUID.randomUUID().toString())
                .success(true)
                .status("EXECUTED")
                .build();
        when(deviceAccessValidator.canAccessDevice(userId, deviceId)).thenReturn(true);
        when(deviceCommandService.sendCommand(eq(deviceId), eq("TURN_ON"), any(), eq(StateChangeSource.MANUAL)))
                .thenReturn(CompletableFuture.completedFuture(result));

        MvcResult mvcResult = mvc.perform(post("/api/v1/devices/{deviceId}/command", deviceId)
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"TURN_ON\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.status").value("EXECUTED"));
    }

    @Test
    void cancelAutomation_authenticated_returnsTimestamp() throws Exception {
        OffsetDateTime now = OffsetDateTime.now();
        when(manualControlService.cancelAutomation(userId, deviceId)).thenReturn(now);

        mvc.perform(post("/api/v1/devices/{deviceId}/automation/cancel", deviceId)
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000));
    }

    @Test
    void resumeAutomation_authenticated_returns200() throws Exception {
        mvc.perform(post("/api/v1/devices/{deviceId}/automation/resume", deviceId)
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000));

        verify(manualControlService).resumeAutomation(userId, deviceId);
    }

    @Test
    void extendAutomation_authenticated_returnsUntil() throws Exception {
        OffsetDateTime until = OffsetDateTime.now().plusHours(1);
        when(deviceAccessValidator.canAccessDevice(userId, deviceId)).thenReturn(true);
        when(manualOverrideService.extendOverride(deviceId, userId, 60)).thenReturn(until);

        mvc.perform(post("/api/v1/devices/{deviceId}/automation/extend", deviceId)
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000));
    }

    @Test
    void getDeviceUptime_authenticated_returnsUptimeResponse() throws Exception {
        DeviceUptimeResponse resp = DeviceUptimeResponse.builder()
                .uptimePercent(99.5)
                .timelinePoints(List.of())
                .build();
        when(deviceAccessValidator.canAccessDevice(userId, deviceId)).thenReturn(true);
        when(deviceProfilingService.getDeviceUptime(eq(deviceId), anyInt())).thenReturn(resp);

        mvc.perform(get("/api/v1/devices/{deviceId}/analytics/uptime", deviceId)
                        .param("days", "7")
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.uptimePercent").value(99.5));
    }

    @Test
    void getDeviceHealth_authenticated_returnsHealthResponse() throws Exception {
        DeviceHealthResponse resp = DeviceHealthResponse.builder()
                .healthPercent(95.0)
                .switchCycles(120)
                .build();
        when(deviceAccessValidator.canAccessDevice(userId, deviceId)).thenReturn(true);
        when(deviceHealthService.getDeviceHealth(deviceId)).thenReturn(resp);

        mvc.perform(get("/api/v1/devices/{deviceId}/health", deviceId)
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.healthPercent").value(95.0));
    }

    @Test
    void getDeviceHistory_authenticated_returnsHistoryList() throws Exception {
        DeviceStateHistoryResponse h = DeviceStateHistoryResponse.builder().id(UUID.randomUUID())
                .source("MANUAL").newState(Map.of("power", true)).build();
        when(deviceService.getDeviceHistory(userId, deviceId)).thenReturn(List.of(h));

        mvc.perform(get("/api/v1/devices/{deviceId}/history", deviceId)
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result[0].source").value("MANUAL"));
    }

    @Test
    void unauthenticatedRequest_returns401() throws Exception {
        mvc.perform(get("/api/v1/homes/{homeId}/devices", homeId))
                .andExpect(status().isUnauthorized());
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

        mvc.perform(get("/api/v1/homes/{homeId}/device-capabilities", homeId)
                        .with(authentication(auth())))
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

        mvc.perform(get("/api/v1/devices/{deviceId}/capabilities", deviceId)
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.id").value(deviceId.toString()))
                .andExpect(jsonPath("$.result.deviceType").value("LIGHT"));
    }
}
