package com.hesta.backend.falldetection.service;

import com.hesta.backend.entity.FallIncident;
import com.hesta.backend.falldetection.dto.FallIncidentEventDTO;
import com.hesta.backend.falldetection.dto.FallIncidentResponseDTO;
import com.hesta.backend.falldetection.enums.FallIncidentState;
import com.hesta.backend.falldetection.enums.FallIncidentType;
import com.hesta.backend.repository.FallIncidentRepository;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.messaging.simp.SimpMessagingTemplate;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.*;
import java.util.concurrent.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class FallIncidentServiceImpl implements FallIncidentService {

    private final Optional<SimpMessagingTemplate> messagingTemplate;

    @Autowired(required = false)
    private FallIncidentRepository fallIncidentRepository;

    @Autowired(required = false)
    private com.hesta.backend.config.mqtt.MqttGateway mqttGateway;

    @Autowired(required = false)
    private com.hesta.backend.repository.HomeMemberRepository homeMemberRepository;

    /** Per-user destination; clients subscribe to "/user" + this. */
    public static final String USER_FALL_DESTINATION = "/topic/fall";

    // In-memory active incidents store & countdown scheduler
    private final Map<UUID, FallIncidentResponseDTO> activeIncidents = new ConcurrentHashMap<>();
    private final Map<String, Long> dedupCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor();

    @Override
    public FallIncidentResponseDTO processIncomingFallEvent(FallIncidentEventDTO eventDTO) {
        log.warn("[FALL DETECTION] Nhận sự kiện té ngã khẩn cấp từ Device: {} | Type: {} | Conf: {}",
                eventDTO.getDeviceId(), eventDTO.getIncidentClassification(), eventDTO.getConfidence());

        // 1. Dedup by eventId to avoid multi-packet repeats
        if (eventDTO.getEventId() != null && !eventDTO.getEventId().isBlank()) {
            Long lastSeen = dedupCache.putIfAbsent(eventDTO.getEventId(), System.currentTimeMillis());
            if (lastSeen != null) {
                log.info("[FALL DEDUP] Bỏ qua sự kiện trùng lặp eventId: {}", eventDTO.getEventId());
                return activeIncidents.values().stream()
                        .filter(i -> eventDTO.getDeviceId() != null && eventDTO.getDeviceId().equals(i.getDeviceId()))
                        .findFirst()
                        .orElse(null);
            }
        }

        // 2. Cooldown deduplication per deviceId (prevent overlapping countdown timers)
        for (FallIncidentResponseDTO active : activeIncidents.values()) {
            if (eventDTO.getDeviceId() != null && eventDTO.getDeviceId().equals(active.getDeviceId())
                    && (active.getState() == FallIncidentState.SUSPECTED_COUNTDOWN || active.getState() == FallIncidentState.ESCALATED_SOS)) {
                log.info("[FALL DEDUP] Thiết bị {} đang có sự cố đếm ngược dở dang {}. Cập nhật telemetry.",
                        eventDTO.getDeviceId(), active.getId());
                active.setMetrics(eventDTO.getMetrics());
                if (eventDTO.getConfidence() != null && eventDTO.getConfidence() > active.getConfidenceScore()) {
                    active.setConfidenceScore(eventDTO.getConfidence());
                }
                broadcastRealtime(active);
                return active;
            }
        }

        // Cleanup old dedup cache entries (> 60s)
        long nowMs = System.currentTimeMillis();
        dedupCache.entrySet().removeIf(e -> nowMs - e.getValue() > 60000);

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
                .roomName("Toàn Cảnh Camera Quan Sát")
                .incidentType(incidentType)
                .state(FallIncidentState.SUSPECTED_COUNTDOWN)
                .confidenceScore(eventDTO.getConfidence() != null ? eventDTO.getConfidence() : 0.95)
                .countdownRemainingSec(30)
                .metrics(eventDTO.getMetrics())
                .detectedAt(Instant.now())
                .build();

        activeIncidents.put(incidentId, response);

        // Lưu vào cơ sở dữ liệu PostgreSQL (nếu có Repository)
        saveToDatabase(response, null);

        // Broadcast WebSocket khẩn cấp tới Web Dashboard & Mobile
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
            saveToDatabase(incident, null);
            broadcastRealtime(incident);
            if (mqttGateway != null) {
                try {
                    mqttGateway.sendToMqtt("hesta/actuators/siren", "{\"command\":\"START\",\"durationSec\":60}");
                    log.info("[MQTT SIREN] Kích hoạt còi hú 12V trên ESP32");
                } catch (Exception e) {
                    log.warn("[MQTT SIREN] Không thể gửi lệnh tới MQTT còi: {}", e.getMessage());
                }
            }
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
            saveToDatabase(incident, incident.getResolutionNote());
            broadcastRealtime(incident);
            activeIncidents.remove(incidentId);
            if (mqttGateway != null) {
                try {
                    mqttGateway.sendToMqtt("hesta/actuators/siren", "{\"command\":\"STOP\"}");
                    log.info("[MQTT SIREN] Tắt còi hú trên ESP32");
                } catch (Exception e) {
                    log.warn("[MQTT SIREN] Không thể gửi lệnh tắt còi: {}", e.getMessage());
                }
            }
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
            saveToDatabase(incident, incident.getResolutionNote());
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
            saveToDatabase(incident, incident.getResolutionNote());
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

    private void saveToDatabase(FallIncidentResponseDTO dto, String note) {
        if (fallIncidentRepository == null) return;
        try {
            FallIncident entity = FallIncident.builder()
                    .id(dto.getId())
                    .homeId(dto.getHomeId())
                    .deviceId(dto.getDeviceId())
                    .roomName(dto.getRoomName())
                    .incidentType(dto.getIncidentType())
                    .state(dto.getState())
                    .confidenceScore(dto.getConfidenceScore())
                    .detectedAt(OffsetDateTime.now())
                    .resolvedAt(dto.getResolvedAt() != null ? OffsetDateTime.now() : null)
                    .resolutionNote(note)
                    .build();
            fallIncidentRepository.save(entity);
            log.debug("[FALL DB] Đã lưu sự cố té ngã vào PostgreSQL: {}", dto.getId());
        } catch (Exception e) {
            log.warn("[FALL DB] Không thể lưu vào database (chạy fallback in-memory): {}", e.getMessage());
        }
    }

    private void broadcastRealtime(FallIncidentResponseDTO incident) {
        messagingTemplate.ifPresent(template -> {
            if (homeMemberRepository == null) return;
            try {
                // Only active members of the incident's home receive it (STOMP principal name = email).
                List<String> recipients = homeMemberRepository.findByHomeIdWithUser(incident.getHomeId()).stream()
                        .filter(m -> m.getStatus() == com.hesta.backend.enums.MemberStatus.ACTIVE)
                        .map(m -> m.getUser().getEmail())
                        .toList();
                recipients.forEach(email -> template.convertAndSendToUser(email, USER_FALL_DESTINATION, incident));
                log.info("[STOMP WS] Đã gửi sự kiện té ngã tới {} thành viên của nhà {}", recipients.size(), incident.getHomeId());
            } catch (Exception e) {
                log.warn("[STOMP WS] Không thể broadcast sự kiện: {}", e.getMessage());
            }
        });
    }

    @PreDestroy
    public void onDestroy() {
        log.info("[FALL DETECTION] Đang tắt ScheduledExecutorService an toàn...");
        scheduler.shutdownNow();
    }
}
