package com.hesta.backend.falldetection;

import com.hesta.backend.falldetection.controller.FallIncidentController;
import com.hesta.backend.falldetection.dto.FallIncidentEventDTO;
import com.hesta.backend.falldetection.dto.FallIncidentResponseDTO;
import com.hesta.backend.falldetection.enums.FallIncidentState;
import com.hesta.backend.falldetection.enums.FallIncidentType;
import com.hesta.backend.falldetection.service.FallIncidentService;
import com.hesta.backend.repository.HomeMemberRepository;
import com.hesta.backend.service.RealtimeSubscriptionService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class FallIncidentControllerTest {

    @Mock
    private FallIncidentService fallIncidentService;

    @Mock
    private RealtimeSubscriptionService homeAccess;

    @Mock
    private HomeMemberRepository homeMemberRepository;

    private FallIncidentController controller;

    @BeforeEach
    void setUp() {
        controller = new FallIncidentController(fallIncidentService, homeAccess, homeMemberRepository);
    }

    @Test
    @DisplayName("Sự kiện có API key hợp lệ: Được chấp nhận và trả về 200 OK")
    void testHandleFallEvent_WithValidKey() {
        ReflectionTestUtils.setField(controller, "internalApiKey", "secret_key_123");

        FallIncidentEventDTO event = FallIncidentEventDTO.builder()
                .deviceId("cam_livingroom_01")
                .eventType("FALL_DETECTED")
                .incidentClassification("ACCIDENTAL_FALL")
                .build();

        FallIncidentResponseDTO mockResponse = FallIncidentResponseDTO.builder()
                .id(UUID.randomUUID())
                .state(FallIncidentState.SUSPECTED_COUNTDOWN)
                .incidentType(FallIncidentType.ACCIDENTAL_FALL)
                .build();

        when(fallIncidentService.processIncomingFallEvent(any())).thenReturn(mockResponse);

        ResponseEntity<FallIncidentResponseDTO> response = controller.handleFallEvent(
                "secret_key_123", null, event);

        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(mockResponse, response.getBody());
        verify(fallIncidentService).processIncomingFallEvent(event);
    }

    @Test
    @DisplayName("Sự kiện có API key sai: Bị từ chối với 401 Unauthorized")
    void testHandleFallEvent_WithInvalidKey() {
        ReflectionTestUtils.setField(controller, "internalApiKey", "secret_key_123");

        FallIncidentEventDTO event = FallIncidentEventDTO.builder()
                .deviceId("cam_livingroom_01")
                .eventType("FALL_DETECTED")
                .build();

        ResponseEntity<FallIncidentResponseDTO> response = controller.handleFallEvent(
                "wrong_token", null, event);

        assertEquals(HttpStatus.UNAUTHORIZED, response.getStatusCode());
    }

    @Test
    @DisplayName("Khi không cấu hình API key (Dev mode): Cho phép nhận sự kiện")
    void testHandleFallEvent_WithoutConfiguredKey() {
        ReflectionTestUtils.setField(controller, "internalApiKey", "");

        FallIncidentEventDTO event = FallIncidentEventDTO.builder()
                .deviceId("cam_livingroom_01")
                .eventType("FALL_DETECTED")
                .build();

        FallIncidentResponseDTO mockResponse = FallIncidentResponseDTO.builder()
                .id(UUID.randomUUID())
                .build();

        when(fallIncidentService.processIncomingFallEvent(any())).thenReturn(mockResponse);

        ResponseEntity<FallIncidentResponseDTO> response = controller.handleFallEvent(
                null, null, event);

        assertEquals(HttpStatus.OK, response.getStatusCode());
    }
}
