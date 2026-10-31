package com.hesta.backend.falldetection.service;

import com.hesta.backend.falldetection.dto.FallIncidentEventDTO;
import com.hesta.backend.falldetection.dto.FallIncidentResponseDTO;
import com.hesta.backend.falldetection.enums.FallIncidentState;
import com.hesta.backend.falldetection.enums.FallIncidentType;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FallIncidentServiceImpl implements FallIncidentService {

    private final Optional<SimpMessagingTemplate> messagingTemplate;

    // Concurrent in-memory store for active incidents and countdown timers
    private final Map<UUID, FallIncidentResponseDTO> activeIncidents = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @Override
    public FallIncidentResponseDTO processIncomingFallEvent(FallIncidentEventDTO eventDTO) {
        log.warn("[FALL DETECTION] Nhận sự kiện té ngã khẩn cấp từ Device: {} | Type: {} | Conf: {}",
                eventDTO.getDeviceId(), eventDTO.getIncidentClassification(), eventDTO.getConfidence());

        UUID incidentId = UUID.randomUUID();
        UUID homeId = (eventDTO.getHomeId() != null && !eventDTO.getHomeId().isBlank())
                ? UUID.fromString(eventDTO.getHomeId())
                : UUID.fromString("00000000-0000-0000-0000-000000000001");

        FallIncidentType incidentType = FallIncidentType.ACCIDENTAL_FALL;
        if (eventDTO.getIncidentClassification() != null) {
            try {
                incidentType = FallIncidentType.valueOf(eventDTO.getIncidentClassification());
            } catch (IllegalArgumentException ignored) {
            }
        }

        FallIncidentResponseDTO response = FallIncidentResponseDTO.builder()
                .id(incidentId)
                .homeId(homeId)
                .deviceId(eventDTO.getDeviceId())
                .roomName("Phòng Khách (Bàn ghế gỗ)")
                .incidentType(incidentType)
                .state(FallIncidentState.SUSPECTED_COUNTDOWN)
                .confidenceScore(eventDTO.getConfidence() != null ? eventDTO.getConfidence() : 0.95)
                .countdownRemainingSec(30)
                .metrics(eventDTO.getMetrics())
                .detectedAt(Instant.now())
                .build();

        activeIncidents.put(incidentId, response);

        // Phát WebSocket thông báo khẩn cấp tới Web Dashboard & Mobile
        broadcastRealtime(response);

        // Khởi động đếm ngược 30 giây (Escalation Pipeline)
        scheduler.schedule(() -> checkEscalationTimeout(incidentId), 30, TimeUnit.SECONDS);

        return response;
    }

    private void checkEscalationTimeout(UUID incidentId) {
        FallIncidentResponseDTO incident = activeIncidents.get(incidentId);
        if (incident != null && incident.getState() == FallIncidentState.SUSPECTED_COUNTDOWN) {
            log.error("[SOS ESCALATION] Hết 30 giây đếm ngược mà nạn nhân không phản hồi! KÍCH HOẠT SOS CẤP TỐC: {}", incidentId);
            incident.setState(FallIncidentState.ESCALATED_SOS);
            incident.setCountdownRemainingSec(0);
            broadcastRealtime(incident);
        }
    }

    @Override
    public FallIncidentResponseDTO dismissIncident(UUID incidentId, String note) {
        log.info("[FALL DISMISS] Người dùng xác nhận TÔI ỔN - HỦY BÁO ĐỘNG cho sự cố: {}", incidentId);
        FallIncidentResponseDTO incident = activeIncidents.get(incidentId);
        if (incident != null) {
            incident.setState(FallIncidentState.FALSE_ALARM);
            incident.setResolvedAt(Instant.now());
            incident.setResolutionNote(note != null ? note : "Người dùng bấm xác nhận an toàn (Tôi ổn)");
            broadcastRealtime(incident);
            activeIncidents.remove(incidentId);
            return incident;
        }
        return null;
    }

    @Override
    public FallIncidentResponseDTO acknowledgeIncident(UUID incidentId, UUID userId) {
        log.info("[FALL ACK] Người thân/Caregiver {} xác nhận đang kiểm tra sự cố: {}", userId, incidentId);
        FallIncidentResponseDTO incident = activeIncidents.get(incidentId);
        if (incident != null) {
            incident.setState(FallIncidentState.ACKNOWLEDGED);
            incident.setResolutionNote("Người thân đang trực tiếp kiểm tra tại hiện trường");
            broadcastRealtime(incident);
            return incident;
        }
        return null;
    }

    @Override
    public FallIncidentResponseDTO resolveIncident(UUID incidentId, UUID userId, String note) {
        log.info("[FALL RESOLVE] Cứu nạn hoàn tất cho sự cố: {}", incidentId);
        FallIncidentResponseDTO incident = activeIncidents.get(incidentId);
        if (incident != null) {
            incident.setState(FallIncidentState.RESOLVED);
            incident.setResolvedAt(Instant.now());
            incident.setResolutionNote(note != null ? note : "Đã xử lý an toàn");
            broadcastRealtime(incident);
            activeIncidents.remove(incidentId);
            return incident;
        }
        return null;
    }

    @Override
    public List<FallIncidentResponseDTO> getActiveIncidents(UUID homeId) {
        return activeIncidents.values().stream()
                .filter(i -> homeId == null || i.getHomeId().equals(homeId))
                .toList();
    }

    private void broadcastRealtime(FallIncidentResponseDTO incident) {
        messagingTemplate.ifPresent(template -> {
            try {
                template.convertAndSend("/topic/emergency/fall", incident);
                template.convertAndSend("/topic/home/" + incident.getHomeId() + "/fall", incident);
                log.info("[STOMP WS] Đã broadcast sự kiện té ngã tới /topic/emergency/fall");
            } catch (Exception e) {
                log.warn("[STOMP WS] Không thể broadcast sự kiện: {}", e.getMessage());
            }
        });
    }
}
