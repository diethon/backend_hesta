package com.hesta.backend.controller;

import com.hesta.backend.config.SecurityConfig;
import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.request.AirConditionerCommandRequest;
import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.security.*;
import com.hesta.backend.service.AirConditionerService;
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

@WebMvcTest(controllers = AirConditionerController.class,
        excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, CustomAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class})
class AirConditionerControllerTest {

    @Autowired
    private MockMvc mvc;

    @MockitoBean
    private AirConditionerService airConditionerService;

    @MockitoBean
    private JwtTokenProvider jwtTokenProvider;

    @MockitoBean
    private CustomUserDetailsService customUserDetailsService;

    private final UUID userId = UUID.randomUUID();
    private final UUID deviceId = UUID.fromString("05d8a4fd-d7f1-4c62-a70e-a4213552b054");

    private UsernamePasswordAuthenticationToken auth() {
        List<SimpleGrantedAuthority> authorities = List.of(new SimpleGrantedAuthority("ROLE_USER"));
        CustomUserDetails principal = new CustomUserDetails(userId, "user@hesta.com", "pass", AccountStatus.ACTIVE, authorities);
        return new UsernamePasswordAuthenticationToken(principal, null, authorities);
    }

    @Test
    void sendCommand_DispatchesSuccessfully() throws Exception {
        CommandResult result = CommandResult.builder()
                .commandId("cmd-123")
                .success(true)
                .status("SUCCESS")
                .build();

        when(airConditionerService.sendCommand(eq(userId), eq(deviceId), any(AirConditionerCommandRequest.class)))
                .thenReturn(CompletableFuture.completedFuture(result));

        String requestBody = "{\"action\":\"SET_TEMPERATURE\",\"temperature\":24}";

        MvcResult mvcResult = mvc.perform(post("/api/v1/devices/{deviceId}/air-conditioner/command", deviceId)
                        .with(authentication(auth()))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.success").value(true))
                .andExpect(jsonPath("$.result.commandId").value("cmd-123"));

        verify(airConditionerService).sendCommand(eq(userId), eq(deviceId), any(AirConditionerCommandRequest.class));
    }

    @Test
    void setPower_DispatchesSuccessfully() throws Exception {
        CommandResult result = CommandResult.builder()
                .commandId("cmd-456")
                .success(true)
                .status("SUCCESS")
                .build();

        when(airConditionerService.setPower(eq(userId), eq(deviceId), eq(true)))
                .thenReturn(CompletableFuture.completedFuture(result));

        MvcResult mvcResult = mvc.perform(post("/api/v1/devices/{deviceId}/air-conditioner/power", deviceId)
                        .with(authentication(auth()))
                        .param("power", "true"))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.success").value(true));

        verify(airConditionerService).setPower(eq(userId), eq(deviceId), eq(true));
    }

    @Test
    void getState_DispatchesSuccessfully() throws Exception {
        CommandResult result = CommandResult.builder()
                .commandId("cmd-789")
                .success(true)
                .status("SUCCESS")
                .build();

        when(airConditionerService.getState(eq(userId), eq(deviceId)))
                .thenReturn(CompletableFuture.completedFuture(result));

        MvcResult mvcResult = mvc.perform(get("/api/v1/devices/{deviceId}/air-conditioner/state", deviceId)
                        .with(authentication(auth())))
                .andExpect(request().asyncStarted())
                .andReturn();

        mvc.perform(asyncDispatch(mvcResult))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.success").value(true));

        verify(airConditionerService).getState(eq(userId), eq(deviceId));
    }
}
