package com.hesta.backend.service.impl;

import com.hesta.backend.dto.request.CreateAutomationRuleRequest;
import com.hesta.backend.dto.request.RuleActionRequest;
import com.hesta.backend.dto.request.ScheduleRequest;
import com.hesta.backend.dto.response.BehaviorPatternResponse;
import com.hesta.backend.dto.response.BehaviorPredictionResponse;
import com.hesta.backend.dto.response.RecommendationResponse;
import com.hesta.backend.entity.AutomationRecommendation;
import com.hesta.backend.entity.Device;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.AutomationRecommendationRepository;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.UserRepository;
import com.hesta.backend.service.AutomationRuleService;
import com.hesta.backend.service.BehaviorService;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.RecommendationService;
import com.hesta.backend.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class RecommendationServiceImpl implements RecommendationService {
    private final HomeAuthorizationService authorization;
    private final BehaviorService behavior;
    private final AutomationRecommendationRepository recommendations;
    private final DeviceRepository devices;
    private final UserRepository users;
    private final AutomationRuleService rules;
    private final ScheduleService schedules;

    @Override
    @Transactional
    public List<RecommendationResponse> generate(UUID userId, UUID homeId, OffsetDateTime from, OffsetDateTime to) {
        var home = authorization.requireSceneManagement(userId, homeId);
        List<AutomationRecommendation> existing = new ArrayList<>(recommendations.findAllByHomeIdOrderByCreatedAtDesc(homeId));
        List<RecommendationResponse> created = new ArrayList<>();
        for (BehaviorPredictionResponse prediction : behavior.predict(userId, homeId, from, to, OffsetDateTime.now())) {
            Device device = devices.findById(prediction.getDeviceId()).orElse(null);
            if (device == null || !homeId.equals(device.getNode().getHome().getId())
                    || !device.supportsAction(prediction.getAction())
                    || duplicate(existing, device.getId(), prediction.getAction(), prediction.getPredictedTime().withSecond(0).toString())) continue;
            AutomationRecommendation saved = recommendations.save(AutomationRecommendation.builder()
                    .home(home).device(device).status("PENDING")
                    .triggerCondition(Map.of("type", "SCHEDULE", "time", prediction.getPredictedTime().withSecond(0).toString(),
                            "repeatDays", List.of(), "patternType", "PREDICTION"))
                    .proposedAction(Map.of("deviceId", device.getId().toString(), "action", prediction.getAction(), "parameters", Map.of()))
                    .confidenceScore(BigDecimal.valueOf(prediction.getConfidence()))
                    .explanation("Dự đoán: " + prediction.getReason()).build());
            existing.add(saved);
            created.add(response(saved));
        }
        for (BehaviorPatternResponse pattern : behavior.detectPatterns(userId, homeId, from, to)) {
            if (pattern.getConfidence() < 0.5 || (!"TURN_ON".equals(pattern.getAction())
                    && !"TURN_OFF".equals(pattern.getAction()))) continue;
            Device device = devices.findById(pattern.getDeviceId()).orElse(null);
            if (device == null || !homeId.equals(device.getNode().getHome().getId())
                    || !device.supportsAction(pattern.getAction())) continue;
            String time = pattern.getAverageTime().withSecond(0).withNano(0).toString();
            if (duplicate(existing, device.getId(), pattern.getAction(), time)) continue;
            AutomationRecommendation saved = recommendations.save(AutomationRecommendation.builder()
                    .home(home).device(device).status("PENDING")
                    .triggerCondition(Map.of("type", "SCHEDULE", "time", time,
                            "repeatDays", List.of(), "patternType", pattern.getPatternType()))
                    .proposedAction(Map.of("deviceId", device.getId().toString(), "action", pattern.getAction(), "parameters", Map.of()))
                    .confidenceScore(BigDecimal.valueOf(pattern.getConfidence()))
                    .explanation("Thiết bị %s được %s khoảng %s trong %d ngày; độ tin cậy %.0f%%."
                            .formatted(device.getName(), "TURN_ON".equals(pattern.getAction()) ? "bật" : "tắt",
                                    time, pattern.getOccurrences(), pattern.getConfidence() * 100)).build());
            existing.add(saved);
            created.add(response(saved));
        }
        return created;
    }

    private boolean duplicate(List<AutomationRecommendation> existing, UUID deviceId, String action, String time) {
        return existing.stream().anyMatch(item -> item.getDevice() != null
                && item.getDevice().getId().equals(deviceId)
                && action.equals(item.getProposedAction().get("action"))
                && time.equals(item.getTriggerCondition().get("time")));
    }

    @Override
    @Transactional(readOnly = true)
    public List<RecommendationResponse> list(UUID userId, UUID homeId) {
        authorization.requireAccess(userId, homeId);
        return recommendations.findAllByHomeIdOrderByCreatedAtDesc(homeId).stream()
                .sorted(Comparator.comparing((AutomationRecommendation item) -> !"PENDING".equals(item.getStatus())))
                .map(this::response).toList();
    }

    @Override
    @Transactional
    public RecommendationResponse approve(UUID userId, UUID homeId, UUID recommendationId) {
        authorization.requireSceneManagement(userId, homeId);
        AutomationRecommendation recommendation = pending(homeId, recommendationId);
        Device device = recommendation.getDevice();
        String action = String.valueOf(recommendation.getProposedAction().get("action"));
        String name = "Gợi ý " + recommendation.getId().toString().substring(0, 8);
        var rule = rules.create(userId, homeId, CreateAutomationRuleRequest.builder()
                .name(name).description(recommendation.getExplanation()).triggerType("SCHEDULE").enabled(true)
                .conditions(List.of()).actions(List.of(RuleActionRequest.builder()
                        .deviceId(device.getId()).action(action).parameters(Map.of()).order(0).build())).build());
        ScheduleRequest schedule = new ScheduleRequest();
        schedule.setScheduledTime(java.time.LocalTime.parse(String.valueOf(recommendation.getTriggerCondition().get("time"))));
        schedule.setRepeatDays(List.of());
        schedule.setActive(true);
        schedules.saveRuleSchedule(userId, homeId, rule.getId(), null, schedule);
        resolve(recommendation, userId, "APPROVED");
        return response(recommendations.save(recommendation));
    }

    @Override
    @Transactional
    public RecommendationResponse reject(UUID userId, UUID homeId, UUID recommendationId) {
        authorization.requireSceneManagement(userId, homeId);
        AutomationRecommendation recommendation = pending(homeId, recommendationId);
        resolve(recommendation, userId, "REJECTED");
        return response(recommendations.save(recommendation));
    }

    private AutomationRecommendation pending(UUID homeId, UUID id) {
        AutomationRecommendation recommendation = recommendations.findByIdAndHomeId(id, homeId)
                .orElseThrow(() -> new AppException(ErrorCode.RECOMMENDATION_NOT_FOUND));
        if (!"PENDING".equals(recommendation.getStatus())) {
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

    private RecommendationResponse response(AutomationRecommendation item) {
        return RecommendationResponse.builder().id(item.getId()).homeId(item.getHome().getId())
                .deviceId(item.getDevice() == null ? null : item.getDevice().getId())
                .deviceName(item.getDevice() == null ? null : item.getDevice().getName())
                .triggerCondition(item.getTriggerCondition()).proposedAction(item.getProposedAction())
                .explanation(item.getExplanation())
                .confidence(item.getConfidenceScore() == null ? 0 : item.getConfidenceScore().doubleValue())
                .status(item.getStatus()).createdAt(item.getCreatedAt())
                .resolvedAt(item.getResolvedAt()).build();
    }

}
