package com.hesta.backend.service.impl;

import com.hesta.backend.dto.request.CreateAutomationRuleRequest;
import com.hesta.backend.dto.request.RuleActionRequest;
import com.hesta.backend.dto.request.ScheduleRequest;
import com.hesta.backend.dto.response.MlModelStatusResponse;
import com.hesta.backend.dto.response.MlRecommendationProposal;
import com.hesta.backend.dto.response.RecommendationResponse;
import com.hesta.backend.entity.AutomationRecommendation;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.AutomationRecommendationRepository;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.HomeRepository;
import com.hesta.backend.repository.UserRepository;
import com.hesta.backend.service.AutomationRuleService;
import com.hesta.backend.service.BehaviorEventRecorder;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.MemberAccessContext;
import com.hesta.backend.enums.AccessLevel;
import com.hesta.backend.enums.AccessScope;
import com.hesta.backend.service.MlServiceClient;
import com.hesta.backend.service.RecommendationService;
import com.hesta.backend.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.*;

@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationServiceImpl implements RecommendationService {

    private final HomeAuthorizationService authorization;
    private final MlServiceClient mlServiceClient;
    private final BehaviorEventRecorder behaviorEventRecorder;
    private final AutomationRecommendationRepository recommendations;
    private final DeviceRepository devices;
    private final UserRepository users;
    private final HomeRepository homeRepository;
    private final AutomationRuleService rules;
    private final ScheduleService schedules;

    @Value("${hesta.recommendation.confidence-threshold:0.80}")
    private double confidenceThreshold;

    @Value("${hesta.recommendation.cooldown-hours:24}")
    private int cooldownHours;

    @Override
    @Transactional
    public List<RecommendationResponse> generate(UUID userId, UUID homeId, OffsetDateTime from, OffsetDateTime to) {
        Home home = authorization.requireSceneManagement(userId, homeId);
        return doGenerate(home, from, to);
    }

    @Override
    @Transactional
    public List<RecommendationResponse> generateForSystem(UUID homeId, OffsetDateTime from, OffsetDateTime to) {
        Home home = homeRepository.findById(homeId)
                .orElseThrow(() -> new AppException(ErrorCode.HOME_NOT_FOUND));
        return doGenerate(home, from, to);
    }

    private List<RecommendationResponse> doGenerate(Home home, OffsetDateTime from, OffsetDateTime to) {
        UUID homeId = home.getId();
        List<AutomationRecommendation> existing = new ArrayList<>(recommendations.findAllByHomeIdOrderByCreatedAtDesc(homeId));
        List<RecommendationResponse> created = new ArrayList<>();

        // 1. Query real ML recommendations from Python ML Service
        List<MlRecommendationProposal> mlProposals = mlServiceClient.generateRecommendations(homeId, from, to, confidenceThreshold);

        for (MlRecommendationProposal proposal : mlProposals) {
            if (proposal.getDeviceId() == null || proposal.getAction() == null) continue;

            Device device = devices.findById(proposal.getDeviceId()).orElse(null);
            if (device == null || device.getRoom() == null || !homeId.equals(device.getRoom().getHome().getId())) {
                continue;
            }

            // Verify device supports action
            if (!device.supportsAction(proposal.getAction())) {
                log.info("Skipping ML recommendation: Device {} does not support action {}", device.getName(), proposal.getAction());
                continue;
            }

            // Strictly check confidence threshold
            double conf = proposal.getConfidence() != null ? proposal.getConfidence() : 0.0;
            if (conf < confidenceThreshold) {
                log.info("Skipping ML recommendation: Confidence {} < threshold {}", conf, confidenceThreshold);
                continue;
            }

            // Deduplication and cooldown check
            String triggerTime = proposal.getTime() != null ? proposal.getTime() : "07:00:00";
            if (isDuplicateOrInCooldown(existing, device.getId(), proposal.getAction(), triggerTime)) {
                log.info("Skipping recommendation for device {}: already pending, active or in cooldown", device.getId());
                continue;
            }

            String actionVerb = getVietnameseActionVerb(proposal.getAction());
            String exp = proposal.getReason() != null ? proposal.getReason() :
                    String.format("AI phát hiện bạn thường %s %s vào khoảng %s với độ tin cậy %d%%.",
                            actionVerb, device.getName(), triggerTime.substring(0, 5), (int) (conf * 100));

            AutomationRecommendation item = recommendations.save(AutomationRecommendation.builder()
                    .home(home)
                    .device(device)
                    .triggerCondition(Map.of("type", "SCHEDULE", "time", triggerTime, "repeatDays", List.of()))
                    .proposedAction(Map.of(
                            "deviceId", device.getId().toString(),
                            "action", proposal.getAction(),
                            "parameters", proposal.getParameters() != null ? proposal.getParameters() : Map.of()
                    ))
                    .explanation(exp)
                    .confidenceScore(BigDecimal.valueOf(conf))
                    .status("PENDING")
                    .engineType(proposal.getEngineType() != null ? proposal.getEngineType() : "ML")
                    .modelVersion(proposal.getModelVersion() != null ? proposal.getModelVersion() : "rf-v1.0.0")
                    .build());

            if (item != null) {
                existing.add(item);
                created.add(response(item));
            }
        }

        return created;
    }

    private boolean isDuplicateOrInCooldown(List<AutomationRecommendation> existing, UUID deviceId, String action, String time) {
        OffsetDateTime now = OffsetDateTime.now();
        OffsetDateTime cooldownLimit = now.minusHours(cooldownHours);

        for (AutomationRecommendation item : existing) {
            if (item.getDevice() == null || !deviceId.equals(item.getDevice().getId())) {
                continue;
            }

            Map<String, Object> proposed = item.getProposedAction();
            if (proposed == null || !action.equalsIgnoreCase(String.valueOf(proposed.get("action")))) {
                continue;
            }

            // If pending recommendation already exists for this device & action -> deduplicate
            if ("PENDING".equalsIgnoreCase(item.getStatus())) {
                return true;
            }

            // If recently accepted (within 7 days) -> no need to re-recommend
            if ("ACCEPTED".equalsIgnoreCase(item.getStatus()) && item.getCreatedAt() != null
                    && item.getCreatedAt().isAfter(now.minusDays(7))) {
                return true;
            }

            // If recently rejected or dismissed within cooldown window -> suppress
            if (("REJECTED".equalsIgnoreCase(item.getStatus()) || "DISMISSED".equalsIgnoreCase(item.getStatus()))
                    && item.getCreatedAt() != null && item.getCreatedAt().isAfter(cooldownLimit)) {
                return true;
            }
        }
        return false;
    }

    @Override
    @Transactional(readOnly = true)
    public List<RecommendationResponse> list(UUID userId, UUID homeId) {
        authorization.requireAccess(userId, homeId);
        MemberAccessContext ctx = authorization.loadAccessContext(homeId, userId);
        var stream = recommendations.findAllByHomeIdOrderByCreatedAtDesc(homeId).stream();
        if (ctx != null && !ctx.isOwner() && ctx.scope() == AccessScope.CUSTOM) {
            stream = stream.filter(r -> r.getDevice() != null && authorization.effectiveLevel(ctx, r.getDevice()) == AccessLevel.CONTROL);
        }
        return stream
                .sorted(Comparator.comparing((AutomationRecommendation item) -> !"PENDING".equals(item.getStatus())))
                .map(this::response)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public RecommendationResponse getTopRecommendation(UUID userId, UUID homeId) {
        authorization.requireAccess(userId, homeId);
        MemberAccessContext ctx = authorization.loadAccessContext(homeId, userId);
        return recommendations.findFirstByHomeIdAndStatusOrderByConfidenceScoreDescCreatedAtDesc(homeId, "PENDING")
                .filter(rec -> rec.getConfidenceScore() != null && rec.getConfidenceScore().doubleValue() >= confidenceThreshold)
                .filter(rec -> {
                    if (ctx == null || ctx.isOwner() || ctx.scope() == AccessScope.ALL) return true;
                    return rec.getDevice() != null && authorization.effectiveLevel(ctx, rec.getDevice()) == AccessLevel.CONTROL;
                })
                .map(this::response)
                .orElse(null);
    }

    @Override
    @Transactional
    public RecommendationResponse approve(UUID userId, UUID homeId, UUID recommendationId) {
        authorization.requireSceneManagement(userId, homeId);
        AutomationRecommendation recommendation = pending(homeId, recommendationId);
        Device device = recommendation.getDevice();
        String action = String.valueOf(recommendation.getProposedAction().get("action"));
        String name = "Gợi ý AI " + recommendation.getId().toString().substring(0, 8);

        Map<String, Object> parameters = new HashMap<>();
        if (recommendation.getProposedAction() != null
                && recommendation.getProposedAction().get("parameters") instanceof Map<?, ?> mapParams) {
            for (Map.Entry<?, ?> entry : mapParams.entrySet()) {
                if (entry.getKey() != null) {
                    parameters.put(entry.getKey().toString(), entry.getValue());
                }
            }
        }

        var rule = rules.create(userId, homeId, CreateAutomationRuleRequest.builder()
                .name(name)
                .description(recommendation.getExplanation())
                .triggerType("SCHEDULE")
                .enabled(true)
                .conditions(List.of())
                .actions(List.of(RuleActionRequest.builder()
                        .deviceId(device.getId())
                        .action(action)
                        .parameters(parameters)
                        .order(0)
                        .build()))
                .build());

        String rawTime = String.valueOf(recommendation.getTriggerCondition().get("time"));
        LocalTime scheduleTime;
        try {
            scheduleTime = LocalTime.parse(rawTime.length() == 5 ? rawTime + ":00" : rawTime);
        } catch (Exception e) {
            scheduleTime = LocalTime.of(7, 0);
        }

        ScheduleRequest schedule = new ScheduleRequest();
        schedule.setScheduledTime(scheduleTime);
        schedule.setRepeatDays(List.of());
        schedule.setActive(true);
        schedules.saveRuleSchedule(userId, homeId, rule.getId(), null, schedule);

        resolve(recommendation, userId, "ACCEPTED");
        AutomationRecommendation saved = recommendations.save(recommendation);

        // Record user feedback telemetry
        behaviorEventRecorder.recordRecommendationFeedback(
                recommendation.getHome(), userId, device, recommendationId,
                "ACCEPTED", recommendation.getEngineType(), recommendation.getModelVersion());

        return response(saved);
    }

    @Override
    @Transactional
    public RecommendationResponse reject(UUID userId, UUID homeId, UUID recommendationId) {
        authorization.requireSceneManagement(userId, homeId);
        AutomationRecommendation recommendation = pending(homeId, recommendationId);
        resolve(recommendation, userId, "REJECTED");
        AutomationRecommendation saved = recommendations.save(recommendation);

        behaviorEventRecorder.recordRecommendationFeedback(
                recommendation.getHome(), userId, recommendation.getDevice(), recommendationId,
                "REJECTED", recommendation.getEngineType(), recommendation.getModelVersion());

        return response(saved);
    }

    @Override
    @Transactional
    public RecommendationResponse dismiss(UUID userId, UUID homeId, UUID recommendationId) {
        authorization.requireSceneManagement(userId, homeId);
        AutomationRecommendation recommendation = pending(homeId, recommendationId);
        resolve(recommendation, userId, "DISMISSED");
        AutomationRecommendation saved = recommendations.save(recommendation);

        behaviorEventRecorder.recordRecommendationFeedback(
                recommendation.getHome(), userId, recommendation.getDevice(), recommendationId,
                "DISMISSED", recommendation.getEngineType(), recommendation.getModelVersion());

        return response(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public MlModelStatusResponse getModelStatus(UUID userId, UUID homeId) {
        authorization.requireAccess(userId, homeId);
        return mlServiceClient.getModelStatus();
    }

    @Override
    @Transactional
    public MlModelStatusResponse triggerRetraining(UUID userId, UUID homeId) {
        authorization.requireSceneManagement(userId, homeId);
        return mlServiceClient.triggerRetraining(homeId);
    }

    private AutomationRecommendation pending(UUID homeId, UUID id) {
        AutomationRecommendation recommendation = recommendations.findByIdAndHomeId(id, homeId)
                .orElseThrow(() -> new AppException(ErrorCode.RECOMMENDATION_NOT_FOUND));
        if (!"PENDING".equalsIgnoreCase(recommendation.getStatus())) {
            throw new AppException(ErrorCode.RECOMMENDATION_ALREADY_RESOLVED);
        }
        return recommendation;
    }

    private void resolve(AutomationRecommendation recommendation, UUID userId, String status) {
        recommendation.setStatus(status);
        recommendation.setResolvedAt(OffsetDateTime.now());
        recommendation.setResolvedBy(users.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND)));
    }

    private String getVietnameseActionVerb(String action) {
        if (action == null) return "điều khiển";
        String act = action.toUpperCase();
        return switch (act) {
            case "TURN_ON", "POWER_ON" -> "bật";
            case "TURN_OFF", "POWER_OFF" -> "tắt";
            case "OPEN", "GATE_OPEN" -> "mở";
            case "CLOSE", "GATE_CLOSE" -> "đóng";
            case "STOP", "GATE_STOP" -> "dừng";
            case "SET_POWER" -> "bật/tắt nguồn";
            case "SET_TEMPERATURE" -> "cài đặt nhiệt độ";
            case "TEMPERATURE_PLUS", "TEMP_UP" -> "tăng nhiệt độ";
            case "TEMPERATURE_MINUS", "TEMP_DOWN" -> "giảm nhiệt độ";
            case "SET_FAN", "SET_SPEED" -> "điều chỉnh tốc độ quạt";
            case "SET_MODE" -> "chuyển chế độ";
            case "SET_SWING" -> "bật/tắt đảo gió";
            case "SET_BRIGHTNESS" -> "điều chỉnh độ sáng";
            case "SET_COLOR", "SET_RGB" -> "đổi màu";
            default -> "điều khiển";
        };
    }

    private RecommendationResponse response(AutomationRecommendation item) {
        if (item == null) return null;
        double conf = item.getConfidenceScore() == null ? 0.0 : item.getConfidenceScore().doubleValue();
        boolean isEligible = conf >= confidenceThreshold;
        String roomName = null;
        if (item.getDevice() != null && item.getDevice().getRoom() != null) {
            roomName = item.getDevice().getRoom().getName();
        }
        return RecommendationResponse.builder()
                .id(item.getId())
                .homeId(item.getHome() != null ? item.getHome().getId() : null)
                .deviceId(item.getDevice() == null ? null : item.getDevice().getId())
                .deviceName(item.getDevice() == null ? null : item.getDevice().getName())
                .roomName(roomName)
                .triggerCondition(item.getTriggerCondition())
                .proposedAction(item.getProposedAction())
                .explanation(item.getExplanation())
                .confidence(conf)
                .status(item.getStatus())
                .engineType(item.getEngineType() != null ? item.getEngineType() : "RULE_BASED")
                .modelVersion(item.getModelVersion())
                .eligible(isEligible)
                .createdAt(item.getCreatedAt())
                .resolvedAt(item.getResolvedAt())
                .build();
    }
}
