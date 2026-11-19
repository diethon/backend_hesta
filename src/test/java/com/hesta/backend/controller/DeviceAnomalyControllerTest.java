package com.hesta.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.request.ResolveAnomalyRequest;
import com.hesta.backend.dto.response.DeviceAnomalyResponse;
import com.hesta.backend.enums.AnomalyStatus;
import com.hesta.backend.enums.AnomalyType;
import com.hesta.backend.enums.SeverityLevel;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.DeviceAnomalyManagementService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.web.PageableHandlerMethodArgumentResolver;
import org.springframework.http.MediaType;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.core.MethodParameter;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@ExtendWith(MockitoExtension.class)
class DeviceAnomalyControllerTest {

    private MockMvc mockMvc;

    @Mock
    private DeviceAnomalyManagementService anomalyManagementService;

    @InjectMocks
    private DeviceAnomalyController controller;

    private ObjectMapper objectMapper = new ObjectMapper();

    private final UUID homeId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID anomalyId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        // Custom argument resolver to inject mock CustomUserDetails
        HandlerMethodArgumentResolver authPrincipalResolver = new HandlerMethodArgumentResolver() {
            @Override
            public boolean supportsParameter(MethodParameter parameter) {
                return parameter.hasParameterAnnotation(AuthenticationPrincipal.class);
            }

            @Override
            public Object resolveArgument(MethodParameter parameter,
                                          ModelAndViewContainer mavContainer,
                                          NativeWebRequest webRequest,
                                          WebDataBinderFactory binderFactory) {
                CustomUserDetails userDetails = org.mockito.Mockito.mock(CustomUserDetails.class);
                when(userDetails.getId()).thenReturn(userId);
                return userDetails;
            }
        };

        mockMvc = MockMvcBuilders.standaloneSetup(controller)
                .setCustomArgumentResolvers(authPrincipalResolver, new PageableHandlerMethodArgumentResolver())
                .build();
    }

    @Test
    @DisplayName("GET /api/v1/homes/{homeId}/anomalies returns 200 and page of anomalies")
    void listAnomalies_Success() throws Exception {
        DeviceAnomalyResponse item = DeviceAnomalyResponse.builder()
                .id(anomalyId)
                .homeId(homeId)
                .deviceId(UUID.randomUUID())
                .deviceName("Sensor 1")
                .anomalyType(AnomalyType.GAS_LEAKAGE)
                .severity(SeverityLevel.HIGH)
                .status(AnomalyStatus.OPEN)
                .detectedAt(OffsetDateTime.now())
                .build();

        Page<DeviceAnomalyResponse> page = new PageImpl<>(List.of(item), PageRequest.of(0, 20), 1);
        when(anomalyManagementService.listAnomalies(eq(userId), eq(homeId), any(), any(), any()))
                .thenReturn(page);

        mockMvc.perform(get("/api/v1/homes/{homeId}/anomalies", homeId)
                        .param("status", "OPEN")
                        .param("severity", "HIGH"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.content[0].id").value(anomalyId.toString()))
                .andExpect(jsonPath("$.result.content[0].severity").value("HIGH"))
                .andExpect(jsonPath("$.result.content[0].status").value("OPEN"));
    }

    @Test
    @DisplayName("PUT /api/v1/homes/{homeId}/anomalies/{id}/acknowledge returns 200 and acknowledged status")
    void acknowledgeAnomaly_Success() throws Exception {
        DeviceAnomalyResponse response = DeviceAnomalyResponse.builder()
                .id(anomalyId)
                .homeId(homeId)
                .status(AnomalyStatus.ACKNOWLEDGED)
                .acknowledgedBy(userId)
                .acknowledgedAt(OffsetDateTime.now())
                .build();

        when(anomalyManagementService.acknowledgeAnomaly(eq(userId), eq(homeId), eq(anomalyId)))
                .thenReturn(response);

        mockMvc.perform(put("/api/v1/homes/{homeId}/anomalies/{id}/acknowledge", homeId, anomalyId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.status").value("ACKNOWLEDGED"))
                .andExpect(jsonPath("$.message").value("Đã xác nhận sự cố bất thường"));
    }

    @Test
    @DisplayName("PUT /api/v1/homes/{homeId}/anomalies/{id}/resolve returns 200 and resolved status")
    void resolveAnomaly_Success() throws Exception {
        ResolveAnomalyRequest req = new ResolveAnomalyRequest(true, "Sensor recalibrated");
        DeviceAnomalyResponse response = DeviceAnomalyResponse.builder()
                .id(anomalyId)
                .homeId(homeId)
                .status(AnomalyStatus.RESOLVED)
                .resolvedBy(userId)
                .falsePositive(true)
                .resolutionNote("Sensor recalibrated")
                .resolvedAt(OffsetDateTime.now())
                .build();

        when(anomalyManagementService.resolveAnomaly(eq(userId), eq(homeId), eq(anomalyId), any()))
                .thenReturn(response);

        mockMvc.perform(put("/api/v1/homes/{homeId}/anomalies/{id}/resolve", homeId, anomalyId)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.status").value("RESOLVED"))
                .andExpect(jsonPath("$.result.falsePositive").value(true))
                .andExpect(jsonPath("$.message").value("Đã xử lý sự cố bất thường thành công"));
    }
}
