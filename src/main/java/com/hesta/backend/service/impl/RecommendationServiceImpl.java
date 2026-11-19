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
import com.hesta.backend.repository.AutomationRuleRepository;
import com.hesta.backend.repository.AutomationScheduleRepository;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Habit recommendations. The ML service proposes "device X, action Y at time T"; this service filters
 * the proposals, stores them as PENDING, and on approval turns one into a scheduled automation rule.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class RecommendationServiceImpl implements RecommendationService {
    /** Proposals closer than this to an existing habit or rule for the same device + action are duplicates. */
    private static final int SAME_HABIT_WINDOW_MINUTES = 60;
    /** An accepted recommendation is not proposed again for this long. */
    private static final int ACCEPTED_QUIET_DAYS = 7;
    /** "Từ chối" means "I don't want this"; only "Bỏ qua" (DISMISSED) uses the short cooldown. */
    private static final int REJECTED_QUIET_DAYS = 30;
    private static final Map<String, String> VIETNAMESE_VERBS = Map.ofEntries(
            Map.entry("TURN_ON", "bật"), Map.entry("POWER_ON", "bật"),
            Map.entry("TURN_OFF", "tắt"), Map.entry("POWER_OFF", "tắt"),
            Map.entry("OPEN", "mở"), Map.entry("GATE_OPEN", "mở"),
            Map.entry("CLOSE", "đóng"), Map.entry("GATE_CLOSE", "đóng"),
            Map.entry("STOP", "dừng"), Map.entry("GATE_STOP", "dừng"),
            Map.entry("SET_POWER", "bật/tắt nguồn"),
            Map.entry("SET_TEMPERATURE", "cài đặt nhiệt độ"),
            Map.entry("TEMPERATURE_PLUS", "tăng nhiệt độ"), Map.entry("TEMP_UP", "tăng nhiệt độ"),
            Map.entry("TEMPERATURE_MINUS", "giảm nhiệt độ"), Map.entry("TEMP_DOWN", "giảm nhiệt độ"),
            Map.entry("SET_FAN", "điều chỉnh tốc độ quạt"), Map.entry("SET_SPEED", "điều chỉnh tốc độ quạt"),
            Map.entry("SET_MODE", "chuyển chế độ"),
            Map.entry("SET_SWING", "bật/tắt đảo gió"),
            Map.entry("SET_BRIGHTNESS", "điều chỉnh độ sáng"),
            Map.entry("SET_COLOR", "đổi màu"), Map.entry("SET_RGB", "đổi màu"));

    private final HomeAuthorizationService authorization;
    private final MlServiceClient mlServiceClient;
    private final BehaviorEventRecorder behaviorEventRecorder;
    private final AutomationRecommendationRepository recommendations;
    private final DeviceRepository devices;
    private final UserRepository users;
    private final HomeRepository homeRepository;
    private final AutomationRuleService rules;
    private final AutomationRuleRepository ruleRepository;
    private final AutomationScheduleRepository ruleSchedules;

    @Value("${hesta.recommendation.confidence-threshold:0.80}")
    private double confidenceThreshold;

    /** A dismissed recommendation is not proposed again for this long. */
    @Value("${hesta.recommendation.cooldown-hours:24}")
    private int cooldownHours;

    // ---------------------------------------------------------------- generating

    @Override
    @Transactional
    public List<RecommendationResponse> generate(UUID userId, UUID homeId, OffsetDateTime from, OffsetDateTime to) {
        return generateFor(authorization.requireSceneManagement(userId, homeId), from, to);
    }

    @Override
    @Transactional
    public List<RecommendationResponse> generateForSystem(UUID homeId, OffsetDateTime from, OffsetDateTime to) {
        Home home = homeRepository.findById(homeId).orElseThrow(() -> new AppException(ErrorCode.HOME_NOT_FOUND));
        return generateFor(home, from, to);
    }

    private List<RecommendationResponse> generateFor(Home home, OffsetDateTime from, OffsetDateTime to) {
        List<AutomationRecommendation> known = new ArrayList<>(recommendations.findAllByHomeIdOrderByCreatedAtDesc(home.getId()));
        List<RecommendationResponse> created = new ArrayList<>();
        for (MlRecommendationProposal proposal : mlServiceClient.generateRecommendations(home.getId(), from, to, confidenceThreshold)) {
            Device device = usableDevice(proposal, home);
            if (device == null) continue;
            String time = proposal.getTime();
            if (time == null || HabitTime.parse(time) == null) {
                log.info("Skipping recommendation for device {}: no valid habit time", device.getId());
                continue;
            }
            if (alreadyKnown(known, device.getId(), proposal.getAction(), time)
                    || coveredByExistingRule(home.getId(), device.getId(), proposal.getAction(), time, proposal.getRepeatDays())) {
                log.info("Skipping recommendation for device {}: pending, recently answered or already a rule", device.getId());
                continue;
            }
            AutomationRecommendation saved = recommendations.save(toEntity(proposal, home, device, time));
            if (saved != null) {
                known.add(saved);
                created.add(response(saved));
            }
        }
        return created;
    }

    /** @return the proposal's device when it belongs to the home, supports the action and confidence is high enough */
    private Device usableDevice(MlRecommendationProposal proposal, Home home) {
        if (proposal.getDeviceId() == null || proposal.getAction() == null) return null;
        Device device = devices.findById(proposal.getDeviceId()).orElse(null);
        if (device == null || device.getRoom() == null || !home.getId().equals(device.getRoom().getHome().getId())) return null;
        if (!device.supportsAction(proposal.getAction())) {
            log.info("Skipping recommendation: device {} does not support {}", device.getName(), proposal.getAction());
            return null;
        }
        if (confidence(proposal) < confidenceThreshold) {
            log.info("Skipping recommendation: confidence {} < threshold {}", confidence(proposal), confidenceThreshold);
            return null;
        }
        return device;
    }

    private AutomationRecommendation toEntity(MlRecommendationProposal proposal, Home home, Device device, String time) {
        String explanation = proposal.getReason() != null ? proposal.getReason()
                : String.format("AI phát hiện bạn thường %s %s vào khoảng %s với độ tin cậy %d%%.",
                verb(proposal.getAction()), device.getName(), time.substring(0, 5), (int) (confidence(proposal) * 100));
        return AutomationRecommendation.builder()
                .home(home)
                .device(device)
                .triggerCondition(Map.of("type", "SCHEDULE", "time", time,
                        "repeatDays", proposal.getRepeatDays() != null ? proposal.getRepeatDays() : List.of()))
                .proposedAction(Map.of(
                        "deviceId", device.getId().toString(),
                        "action", proposal.getAction(),
                        "parameters", proposal.getParameters() != null ? proposal.getParameters() : Map.of()))
                .explanation(explanation)
                .confidenceScore(BigDecimal.valueOf(confidence(proposal)))
                .status("PENDING")
                .engineType(proposal.getEngineType() != null ? proposal.getEngineType() : "ML")
                .modelVersion(proposal.getModelVersion() != null ? proposal.getModelVersion() : "rf-v1.0.0")
                .build();
    }

    /**
     * Same device + action at about the same time is a duplicate when it is still pending,
     * was accepted in the last week, or was rejected/dismissed within the cooldown.
     */
    private boolean alreadyKnown(List<AutomationRecommendation> known, UUID deviceId, String action, String time) {
        OffsetDateTime now = OffsetDateTime.now();
        for (AutomationRecommendation item : known) {
            if (item.getDevice() == null || !deviceId.equals(item.getDevice().getId())) continue;
            Map<String, Object> proposed = item.getProposedAction();
            if (proposed == null || !action.equalsIgnoreCase(String.valueOf(proposed.get("action")))) continue;
            Object itemTime = item.getTriggerCondition() == null ? null : item.getTriggerCondition().get("time");
            if (itemTime != null && !HabitTime.close(String.valueOf(itemTime), time)) continue; // morning vs evening habit

            String status = String.valueOf(item.getStatus()).toUpperCase(Locale.ROOT);
            OffsetDateTime answeredAt = item.getResolvedAt() != null ? item.getResolvedAt() : item.getCreatedAt();
            switch (status) {
                case "PENDING" -> {
                    return true;
                }
                case "ACCEPTED" -> {
                    if (answeredAt != null && answeredAt.isAfter(now.minusDays(ACCEPTED_QUIET_DAYS))) return true;
                }
                case "REJECTED" -> {
                    if (answeredAt != null && answeredAt.isAfter(now.minusDays(REJECTED_QUIET_DAYS))) return true;
                }
                case "DISMISSED" -> {
                    if (answeredAt != null && answeredAt.isAfter(now.minusHours(cooldownHours))) return true;
                }
                default -> { }
            }
        }
        return false;
    }

    /** An enabled scheduled rule already does this at about the same time on the same days. */
    private boolean coveredByExistingRule(UUID homeId, UUID deviceId, String action, String time, List<Integer> repeatDays) {
        LocalTime proposed = HabitTime.parse(time);
        return proposed != null && ruleSchedules.findActiveForDeviceAction(homeId, deviceId, action).stream()
                .anyMatch(existing -> HabitTime.minutesApart(existing.getScheduledTime(), proposed) <= SAME_HABIT_WINDOW_MINUTES
                        && coversDays(existing.getRepeatDays(), repeatDays));
    }

    /** Empty repeat days = every day. A weekday rule does not cover a weekend habit. */
    public static boolean coversDays(Short[] ruleDays, List<Integer> habitDays) {
        if (ruleDays == null || ruleDays.length == 0) return true;
        List<Integer> rule = java.util.Arrays.stream(ruleDays).map(Short::intValue).toList();
        List<Integer> habit = habitDays == null || habitDays.isEmpty() ? List.of(1, 2, 3, 4, 5, 6, 7) : habitDays;
        return rule.containsAll(habit);
    }

    // ---------------------------------------------------------------- reading

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

    // ---------------------------------------------------------------- answering

    /** Accepting creates an enabled SCHEDULE rule with one device action at the recommended time. */
    @Override
    @Transactional
    public RecommendationResponse approve(UUID userId, UUID homeId, UUID recommendationId) {
        authorization.requireSceneManagement(userId, homeId);
        AutomationRecommendation recommendation = pending(homeId, recommendationId);
        Device device = recommendation.getDevice();
        String action = String.valueOf(recommendation.getProposedAction().get("action"));
        String rawTime = String.valueOf(recommendation.getTriggerCondition().get("time"));
        LocalTime time = HabitTime.parse(rawTime);
        if (time == null) throw new AppException(ErrorCode.AUTOMATION_ACTION_INVALID);
        ScheduleRequest schedule = new ScheduleRequest();
        schedule.setScheduledTime(time);
        schedule.setRepeatDays(repeatDays(recommendation.getTriggerCondition()));
        schedule.setActive(true);

        rules.create(userId, homeId, CreateAutomationRuleRequest.builder()
                .name(uniqueRuleName(homeId, device, action, rawTime))
                .description(recommendation.getExplanation())
                .triggerType("SCHEDULE")
                .enabled(true)
                .conditions(List.of())
                .actions(List.of(RuleActionRequest.builder()
                        .deviceId(device.getId())
                        .action(action)
                        .parameters(proposedParameters(recommendation, action))
                        .order(0)
                        .build()))
                .schedules(List.of(schedule))
                .build());

        return answer(recommendation, userId, "ACCEPTED");
    }

    @Override
    @Transactional
    public RecommendationResponse reject(UUID userId, UUID homeId, UUID recommendationId) {
        authorization.requireSceneManagement(userId, homeId);
        return answer(pending(homeId, recommendationId), userId, "REJECTED");
    }

    @Override
    @Transactional
    public RecommendationResponse dismiss(UUID userId, UUID homeId, UUID recommendationId) {
        authorization.requireSceneManagement(userId, homeId);
        return answer(pending(homeId, recommendationId), userId, "DISMISSED");
    }

    /** Stores the user's answer and feeds it back to the ML training data. */
    private RecommendationResponse answer(AutomationRecommendation recommendation, UUID userId, String status) {
        recommendation.setStatus(status);
        recommendation.setResolvedAt(OffsetDateTime.now());
        recommendation.setResolvedBy(users.findById(userId).orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND)));
        AutomationRecommendation saved = recommendations.save(recommendation);
        behaviorEventRecorder.recordRecommendationFeedback(
                recommendation.getHome(), userId, recommendation.getDevice(), recommendation.getId(),
                status, recommendation.getEngineType(), recommendation.getModelVersion());
        return response(saved);
    }

    private AutomationRecommendation pending(UUID homeId, UUID id) {
        AutomationRecommendation recommendation = recommendations.findByIdAndHomeId(id, homeId)
                .orElseThrow(() -> new AppException(ErrorCode.RECOMMENDATION_NOT_FOUND));
        if (!"PENDING".equalsIgnoreCase(recommendation.getStatus())) {
            throw new AppException(ErrorCode.RECOMMENDATION_ALREADY_RESOLVED);
        }
        return recommendation;
    }

    /** "AI: Đèn phòng khách bật 07:00", with " (2)", " (3)"... when that name is taken. */
    private String uniqueRuleName(UUID homeId, Device device, String action, String rawTime) {
        String time = rawTime.length() >= 5 ? " " + rawTime.substring(0, 5) : "";
        String base = "AI: " + device.getName() + " " + verb(action) + time;
        if (base.length() > 140) base = base.substring(0, 140); // column limit is 150, leave room for the suffix
        String name = base;
        for (int suffix = 2; ruleRepository.existsByHomeIdAndName(homeId, name); suffix++) {
            name = base + " (" + suffix + ")";
        }
        return name;
    }

    /** Learned settings only make sense when turning on; "power" is implied by the action itself. */
    public static Map<String, Object> proposedParameters(AutomationRecommendation recommendation, String action) {
        Map<String, Object> parameters = new HashMap<>();
        if (!com.hesta.backend.service.execution.DeviceActionValues.turnsOn(action, null)) return parameters;
        if (recommendation.getProposedAction() != null
                && recommendation.getProposedAction().get("parameters") instanceof Map<?, ?> stored) {
            stored.forEach((key, value) -> {
                if (key != null && !"power".equalsIgnoreCase(key.toString())) parameters.put(key.toString(), value);
            });
        }
        return parameters;
    }

    private static List<Short> repeatDays(Map<String, Object> triggerCondition) {
        if (triggerCondition == null || !(triggerCondition.get("repeatDays") instanceof List<?> days)) return List.of();
        return days.stream().filter(Number.class::isInstance).map(day -> ((Number) day).shortValue())
                .filter(day -> day >= 1 && day <= 7).distinct().toList();
    }

    // ---------------------------------------------------------------- ML model

    @Override
    @Transactional(readOnly = true)
    public MlModelStatusResponse getModelStatus(UUID userId, UUID homeId) {
        authorization.requireAccess(userId, homeId);
        return mlServiceClient.getModelStatus(homeId);
    }

    @Override
    // No transaction: training can take minutes and must not hold a DB connection meanwhile.
    public MlModelStatusResponse triggerRetraining(UUID userId, UUID homeId) {
        authorization.requireSceneManagement(userId, homeId);
        return mlServiceClient.triggerRetraining(homeId);
    }

    // ---------------------------------------------------------------- small helpers

    private static double confidence(MlRecommendationProposal proposal) {
        return proposal.getConfidence() != null ? proposal.getConfidence() : 0.0;
    }

    private static String verb(String action) {
        return action == null ? "điều khiển" : VIETNAMESE_VERBS.getOrDefault(action.toUpperCase(Locale.ROOT), "điều khiển");
    }

    private RecommendationResponse response(AutomationRecommendation item) {
        if (item == null) return null;
        double confidence = item.getConfidenceScore() == null ? 0.0 : item.getConfidenceScore().doubleValue();
        Device device = item.getDevice();
        return RecommendationResponse.builder()
                .id(item.getId())
                .homeId(item.getHome() != null ? item.getHome().getId() : null)
                .deviceId(device == null ? null : device.getId())
                .deviceName(device == null ? null : device.getName())
                .roomName(device != null && device.getRoom() != null ? device.getRoom().getName() : null)
                .triggerCondition(item.getTriggerCondition())
                .proposedAction(item.getProposedAction())
                .explanation(item.getExplanation())
                .confidence(confidence)
                .status(item.getStatus())
                .engineType(item.getEngineType() != null ? item.getEngineType() : "RULE_BASED")
                .modelVersion(item.getModelVersion())
                .eligible(confidence >= confidenceThreshold)
                .createdAt(item.getCreatedAt())
                .resolvedAt(item.getResolvedAt())
                .build();
    }

    /** Clock-time helpers for "HH:mm" / "HH:mm:ss" habit times. */
    static final class HabitTime {
        private HabitTime() {}

        static LocalTime parse(String raw) {
            try {
                return LocalTime.parse(raw.length() == 5 ? raw + ":00" : raw);
            } catch (RuntimeException ex) {
                return null;
            }
        }

        /** Unparseable times count as close, so they still deduplicate. */
        static boolean close(String left, String right) {
            LocalTime a = parse(left);
            LocalTime b = parse(right);
            return a == null || b == null || minutesApart(a, b) <= SAME_HABIT_WINDOW_MINUTES;
        }

        /** Distance on a 24h clock: 23:30 and 00:10 are 40 minutes apart. */
        static int minutesApart(LocalTime a, LocalTime b) {
            int diff = Math.abs(a.toSecondOfDay() - b.toSecondOfDay()) / 60;
            return Math.min(diff, 1440 - diff);
        }
    }
}
