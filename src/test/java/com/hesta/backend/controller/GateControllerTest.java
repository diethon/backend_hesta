package com.hesta.backend.controller;

import com.hesta.backend.config.SecurityConfig;
import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.request.GateCommandRequest;
import com.hesta.backend.dto.response.GateStateResponse;
import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.security.*;
import com.hesta.backend.service.GateService;
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

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.asyncDispatch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = GateController.class,
        excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, CustomAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class})
class GateControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private GateService gateService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    private final UUID userId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();

    private UsernamePasswordAuthenticationToken auth() {
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        CustomUserDetails principal = new CustomUserDetails(userId, "user@hesta.com", "pass", AccountStatus.ACTIVE, authorities);
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    @Test
    void open_ReturnsOk() throws Exception {
        CommandResult result = CommandResult.builder().commandId("cmd-1").success(true).status("SUCCESS").build();
        when(gateService.open(eq(userId), eq(deviceId), any())).thenReturn(CompletableFuture.completedFuture(result));

        MvcResult mvcResult = mvc.perform(post("/api/v1/devices/{deviceId}/gate/open", deviceId)
                        .with(authentication(auth())))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.success").value(true));

        verify(gateService).open(eq(userId), eq(deviceId), any());
    }

    @Test
    void close_ReturnsOk() throws Exception {
        CommandResult result = CommandResult.builder().commandId("cmd-2").success(true).status("SUCCESS").build();
        when(gateService.close(eq(userId), eq(deviceId), any())).thenReturn(CompletableFuture.completedFuture(result));

        MvcResult mvcResult = mvc.perform(post("/api/v1/devices/{deviceId}/gate/close", deviceId)
                        .with(authentication(auth())))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.success").value(true));

        verify(gateService).close(eq(userId), eq(deviceId), any());
    }

    @Test
    void stop_ReturnsOk() throws Exception {
        CommandResult result = CommandResult.builder().commandId("cmd-3").success(true).status("SUCCESS").build();
        when(gateService.stop(eq(userId), eq(deviceId), any())).thenReturn(CompletableFuture.completedFuture(result));

        MvcResult mvcResult = mvc.perform(post("/api/v1/devices/{deviceId}/gate/stop", deviceId)
                        .with(authentication(auth())))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.success").value(true));

        verify(gateService).stop(eq(userId), eq(deviceId), any());
    }

    @Test
    void sendCommand_ReturnsOk() throws Exception {
        CommandResult result = CommandResult.builder().commandId("cmd-4").success(true).status("SUCCESS").build();
        when(gateService.sendCommand(eq(userId), eq(deviceId), any(GateCommandRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(result));

        MvcResult mvcResult = mvc.perform(post("/api/v1/devices/{deviceId}/gate/command", deviceId)
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\": \"OPEN\"}"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.success").value(true));
    }

    @Test
    void getState_ReturnsOk() throws Exception {
        GateStateResponse response = GateStateResponse.builder()
                .deviceId(deviceId)
                .state("OPEN")
                .currentState(Map.of("state", "OPEN"))
                .build();
        when(gateService.getState(eq(userId), eq(deviceId))).thenReturn(response);

        mvc.perform(get("/api/v1/devices/{deviceId}/gate/state", deviceId)
                        .with(authentication(auth())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.state").value("OPEN"))
                .andExpect(jsonPath("$.result.currentState.state").value("OPEN"));
    }
}
