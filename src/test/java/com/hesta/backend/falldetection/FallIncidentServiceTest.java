package com.hesta.backend.falldetection;

import com.hesta.backend.falldetection.dto.FallIncidentEventDTO;
import com.hesta.backend.falldetection.dto.FallIncidentResponseDTO;
import com.hesta.backend.falldetection.dto.FallMetricsDTO;
import com.hesta.backend.falldetection.enums.FallIncidentState;
import com.hesta.backend.falldetection.enums.FallIncidentType;
import com.hesta.backend.falldetection.service.FallIncidentServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.messaging.simp.SimpMessagingTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

@ExtendWith(MockitoExtension.class)
class FallIncidentServiceTest {

    @Mock
    private SimpMessagingTemplate messagingTemplate;

    private FallIncidentServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new FallIncidentServiceImpl(Optional.of(messagingTemplate));
    }

    @Test
    @DisplayName("Tiếp nhận sự kiện té ngã: Khởi tạo sự cố ở trạng thái SUSPECTED_COUNTDOWN")
    void testProcessIncomingFallEvent() {
        FallIncidentEventDTO event = FallIncidentEventDTO.builder()
                .deviceId("cam_livingroom_01")
                .eventType("FALL_DETECTED")
                .incidentClassification("ACCIDENTAL_FALL")
                .confidence(0.96)
                .metrics(FallMetricsDTO.builder()
                        .peakImpactAccelG(3.4)
                        .normalizedVerticalVelocity(2.1)
                        .postureTransitionDurationSec(0.6)
                        .build())
                .timestamp(System.currentTimeMillis())
                .build();

        FallIncidentResponseDTO response = service.processIncomingFallEvent(event);

        assertNotNull(response);
        assertNotNull(response.getId());
        assertEquals(FallIncidentState.SUSPECTED_COUNTDOWN, response.getState());
        assertEquals(FallIncidentType.ACCIDENTAL_FALL, response.getIncidentType());
        assertEquals(30, response.getCountdownRemainingSec());
        assertEquals(0.96, response.getConfidenceScore());

        List<FallIncidentResponseDTO> active = service.getActiveIncidents(null);
        assertEquals(1, active.size());
    }

    @Test
    @DisplayName("Người dùng bấm Tôi ổn: Hủy cảnh báo và chuyển trạng thái sang FALSE_ALARM")
    void testDismissIncident() {
        FallIncidentEventDTO event = FallIncidentEventDTO.builder()
                .deviceId("cam_livingroom_01")
                .eventType("FALL_DETECTED")
                .incidentClassification("ACCIDENTAL_FALL")
                .confidence(0.92)
                .build();

        FallIncidentResponseDTO initial = service.processIncomingFallEvent(event);
        UUID incidentId = initial.getId();

        FallIncidentResponseDTO dismissed = service.dismissIncident(incidentId, "Bấm nhầm nút khi tập thể dục");

        assertNotNull(dismissed);
        assertEquals(FallIncidentState.FALSE_ALARM, dismissed.getState());
        assertEquals("Bấm nhầm nút khi tập thể dục", dismissed.getResolutionNote());
        assertNotNull(dismissed.getResolvedAt());

        List<FallIncidentResponseDTO> active = service.getActiveIncidents(null);
        assertTrue(active.isEmpty());
    }

    @Test
    @DisplayName("Người thân bấm Đang kiểm tra: Chuyển trạng thái sang ACKNOWLEDGED")
    void testAcknowledgeIncident() {
        FallIncidentEventDTO event = FallIncidentEventDTO.builder()
                .deviceId("cam_livingroom_01")
                .eventType("FALL_DETECTED")
                .incidentClassification("SAFE_ZONE_IMPACT_FALL")
                .confidence(0.95)
                .build();

        FallIncidentResponseDTO initial = service.processIncomingFallEvent(event);
        UUID incidentId = initial.getId();
        UUID caregiverId = UUID.randomUUID();

        FallIncidentResponseDTO acked = service.acknowledgeIncident(incidentId, caregiverId);

        assertNotNull(acked);
        assertEquals(FallIncidentState.ACKNOWLEDGED, acked.getState());
    }

    @Test
    @DisplayName("Cứu nạn hoàn tất: Chuyển trạng thái sang RESOLVED")
    void testResolveIncident() {
        FallIncidentEventDTO event = FallIncidentEventDTO.builder()
                .deviceId("cam_livingroom_01")
                .eventType("FALL_DETECTED")
                .incidentClassification("ACCIDENTAL_FALL")
                .confidence(0.98)
                .build();

        FallIncidentResponseDTO initial = service.processIncomingFallEvent(event);
        UUID incidentId = initial.getId();

        FallIncidentResponseDTO resolved = service.resolveIncident(incidentId, UUID.randomUUID(), "Cụ đã được đỡ ngồi dậy an toàn");

        assertNotNull(resolved);
        assertEquals(FallIncidentState.RESOLVED, resolved.getState());
        assertNotNull(resolved.getResolvedAt());
    }
}
