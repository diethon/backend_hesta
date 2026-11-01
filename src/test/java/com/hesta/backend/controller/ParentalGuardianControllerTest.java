package com.hesta.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.request.CreateParentalGuardianRequest;
import com.hesta.backend.dto.request.GuardianSimulateEventRequest;
import com.hesta.backend.dto.response.ParentalGuardianResponse;
import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.enums.GuardianStatus;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.ParentalGuardianService;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ParentalGuardianControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private ParentalGuardianService guardianService;

    private final UUID homeId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID ruleId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();

    private UsernamePasswordAuthenticationToken auth;

    @BeforeEach
    void setUp() {
        CustomUserDetails userDetails = new CustomUserDetails(
                userId,
                "owner@hesta.vn",
                "hashedpassword",
                AccountStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority("ROLE_USER"))
        );
        auth = new UsernamePasswordAuthenticationToken(userDetails, null, userDetails.getAuthorities());
    }

    @Test
    @DisplayName("POST /api/v1/homes/{homeId}/guardian-rules tạo quy tắc thành công")
    void testCreateRuleEndpoint() throws Exception {
        CreateParentalGuardianRequest request = CreateParentalGuardianRequest.builder()
                .name("Giới hạn xem TV khi vắng nhà")
                .targetDeviceId(deviceId)
                .allowedDurationMinutes(45)
                .warningMinutesBefore(5)
                .autoLockout(true)
                .build();

        ParentalGuardianResponse mockResponse = ParentalGuardianResponse.builder()
                .id(ruleId)
                .homeId(homeId)
                .targetDeviceId(deviceId)
                .targetDeviceName("Smart TV")
                .name("Giới hạn xem TV khi vắng nhà")
                .allowedDurationMinutes(45)
                .status(GuardianStatus.IDLE)
                .build();

        when(guardianService.createRule(eq(userId), eq(homeId), any())).thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/homes/{homeId}/guardian-rules", homeId)
                        .with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.id").value(ruleId.toString()))
                .andExpect(jsonPath("$.result.name").value("Giới hạn xem TV khi vắng nhà"));
    }

    @Test
    @DisplayName("GET /api/v1/homes/{homeId}/guardian-rules trả danh sách quy tắc")
    void testListRulesEndpoint() throws Exception {
        ParentalGuardianResponse mockResponse = ParentalGuardianResponse.builder()
                .id(ruleId)
                .homeId(homeId)
                .targetDeviceId(deviceId)
                .name("Giới hạn xem TV khi vắng nhà")
                .status(GuardianStatus.IDLE)
                .build();

        when(guardianService.getRules(eq(userId), eq(homeId))).thenReturn(List.of(mockResponse));

        mockMvc.perform(get("/api/v1/homes/{homeId}/guardian-rules", homeId)
                        .with(authentication(auth)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result[0].id").value(ruleId.toString()));
    }

    @Test
    @DisplayName("POST /api/v1/homes/{homeId}/guardian-rules/{ruleId}/simulate chạy giả lập")
    void testSimulateEndpoint() throws Exception {
        GuardianSimulateEventRequest request = GuardianSimulateEventRequest.builder()
                .eventType("PARENT_DEPARTURE")
                .build();

        ParentalGuardianResponse mockResponse = ParentalGuardianResponse.builder()
                .id(ruleId)
                .homeId(homeId)
                .targetDeviceId(deviceId)
                .name("Giới hạn xem TV khi vắng nhà")
                .parentAway(true)
                .status(GuardianStatus.IDLE)
                .build();

        when(guardianService.simulate(eq(userId), eq(homeId), eq(ruleId), any())).thenReturn(mockResponse);

        mockMvc.perform(post("/api/v1/homes/{homeId}/guardian-rules/{ruleId}/simulate", homeId, ruleId)
                        .with(authentication(auth))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.parentAway").value(true));
    }
}
