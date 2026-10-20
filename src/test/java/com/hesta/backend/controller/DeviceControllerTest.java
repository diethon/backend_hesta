package com.hesta.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.config.SecurityConfig;
import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.request.DeviceUpdateRequest;
import com.hesta.backend.dto.response.DeviceHealthResponse;
import com.hesta.backend.dto.response.DeviceResponse;
import com.hesta.backend.dto.response.DeviceStateHistoryResponse;
import com.hesta.backend.dto.response.DeviceUptimeResponse;
import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.enums.StateChangeSource;
import com.hesta.backend.security.*;
import com.hesta.backend.service.*;
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
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        CustomUserDetails principal = new CustomUserDetails(userId, "user@hesta.com", "pass", AccountStatus.ACTIVE, authorities);
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    @Test
    void getDevicesByHome_returnsList() throws Exception {
        DeviceResponse dev = DeviceResponse.builder().id(deviceId).name("Living Light")
                .deviceType("LIGHT").status("ONLINE").build();
        when(deviceService.getDevicesByHome(userId, homeId)).thenReturn(List.of(dev));

        mvc.perform(get("/api/v1/homes/{homeId}/devices", homeId)
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result[0].name").value("Living Light"));
    }

    @Test
    void getDevicesByRoom_returnsList() throws Exception {
        DeviceResponse dev = DeviceResponse.builder().id(deviceId).name("Fan")
                .deviceType("FAN").status("ONLINE").build();
        when(deviceService.getDevicesByRoom(userId, roomId)).thenReturn(List.of(dev));

        mvc.perform(get("/api/v1/rooms/{roomId}/devices", roomId)
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result[0].name").value("Fan"));
    }

    @Test
    void getDeviceDetail_returnsDetail() throws Exception {
        DeviceResponse dev = DeviceResponse.builder().id(deviceId).name("AC")
                .deviceType("AIR_CONDITIONER").status("ONLINE")
                .settings(Map.of("temp", 24)).build();
        when(deviceService.getDeviceDetail(userId, deviceId)).thenReturn(dev);

        mvc.perform(get("/api/v1/devices/{deviceId}", deviceId)
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.name").value("AC"));
    }

    @Test
    void updateDeviceConfig_updatesAndReturnsOk() throws Exception {
        DeviceUpdateRequest req = new DeviceUpdateRequest();
        req.setName("New AC Name");

        DeviceResponse dev = DeviceResponse.builder().id(deviceId).name("New AC Name").build();
        when(deviceService.updateDeviceConfig(eq(userId), eq(deviceId), any(DeviceUpdateRequest.class))).thenReturn(dev);

        mvc.perform(put("/api/v1/devices/{deviceId}", deviceId)
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("Cập nhật thiết bị thành công"));
    }

    @Test
    void removeDevice_returnsOk() throws Exception {
        mvc.perform(delete("/api/v1/devices/{deviceId}", deviceId)
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.message").value("Xoá thiết bị thành công"));

        verify(deviceService).removeDevice(userId, deviceId);
    }

    @Test
    void sendCommand_asyncDispatchesAndReturnsResult() throws Exception {
        when(deviceAccessValidator.canAccessDevice(userId, deviceId)).thenReturn(true);
        CommandResult cmdResult = CommandResult.builder().success(true).status("ACKNOWLEDGED").build();
        when(deviceCommandService.sendCommand(eq(deviceId), eq("TURN_ON"), any(), eq(StateChangeSource.MANUAL)))
                .thenReturn(CompletableFuture.completedFuture(cmdResult));

        MvcResult asyncListener = mvc.perform(post("/api/v1/devices/{deviceId}/command", deviceId)
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"TURN_ON\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(asyncListener))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.success").value(true));
    }

    @Test
    void cancelAutomation_callsServiceAndReturnsOk() throws Exception {
        OffsetDateTime until = OffsetDateTime.now().plusMinutes(30);
        when(manualControlService.cancelAutomation(userId, deviceId)).thenReturn(until);

        mvc.perform(post("/api/v1/devices/{deviceId}/automation/cancel", deviceId)
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000));
    }

    @Test
    void getDeviceHealth_returnsHealthData() throws Exception {
        when(deviceAccessValidator.canAccessDevice(userId, deviceId)).thenReturn(true);
        DeviceHealthResponse health = DeviceHealthResponse.builder()
                .switchCycles(120)
                .healthPercent(98.5)
                .build();
        when(deviceHealthService.getDeviceHealth(deviceId)).thenReturn(health);

        mvc.perform(get("/api/v1/devices/{deviceId}/health", deviceId)
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.switchCycles").value(120));
    }

    @Test
    void getDeviceHistory_returnsHistoryList() throws Exception {
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
}
