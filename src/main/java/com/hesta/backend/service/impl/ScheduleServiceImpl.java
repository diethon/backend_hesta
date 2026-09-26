package com.hesta.backend.service.impl;

import com.hesta.backend.dto.request.ScheduleRequest;
import com.hesta.backend.dto.response.ScheduleResponse;
import com.hesta.backend.entity.AutomationRule;
import com.hesta.backend.entity.AutomationSchedule;
import com.hesta.backend.entity.Scene;
import com.hesta.backend.entity.SceneSchedule;
import com.hesta.backend.enums.TriggerType;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.AutomationRuleRepository;
import com.hesta.backend.repository.AutomationScheduleRepository;
import com.hesta.backend.repository.SceneRepository;
import com.hesta.backend.repository.SceneScheduleRepository;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.ScheduleService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalTime;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class ScheduleServiceImpl implements ScheduleService {
    private final HomeAuthorizationService authorization;
    private final SceneRepository scenes;
    private final SceneScheduleRepository sceneSchedules;
    private final AutomationRuleRepository rules;
    private final AutomationScheduleRepository ruleSchedules;

    @Value("${hesta.scheduler.zone:Asia/Ho_Chi_Minh}")
    private String zoneName;

    @Override
    @Transactional(readOnly = true)
    public List<ScheduleResponse> sceneSchedules(UUID userId, UUID homeId, UUID sceneId) {
        authorization.requireAccess(userId, homeId);
        scene(homeId, sceneId);
        return sceneSchedules.findAllBySceneIdOrderByScheduledTimeAsc(sceneId).stream()
                .map(this::response).toList();
    }

    @Override
    @Transactional
    public ScheduleResponse saveSceneSchedule(UUID userId, UUID homeId, UUID sceneId, UUID scheduleId, ScheduleRequest request) {
        authorization.requireSceneManagement(userId, homeId);
        Scene scene = scene(homeId, sceneId);
        Short[] days = validate(request);
        SceneSchedule schedule = scheduleId == null ? SceneSchedule.builder().scene(scene).build()
                : sceneSchedules.findById(scheduleId).filter(item -> item.getScene().getId().equals(sceneId))
                .orElseThrow(() -> new AppException(ErrorCode.SCENE_SCHEDULE_NOT_FOUND));
        schedule.setScheduledTime(request.getScheduledTime().withSecond(0).withNano(0));
        schedule.setRepeatDays(days);
        schedule.setActive(request.getActive());
        return response(sceneSchedules.save(schedule));
    }

    @Override
    @Transactional
    public void deleteSceneSchedule(UUID userId, UUID homeId, UUID sceneId, UUID scheduleId) {
        authorization.requireSceneManagement(userId, homeId);
        scene(homeId, sceneId);
        SceneSchedule schedule = sceneSchedules.findById(scheduleId)
                .filter(item -> item.getScene().getId().equals(sceneId))
                .orElseThrow(() -> new AppException(ErrorCode.SCENE_SCHEDULE_NOT_FOUND));
        sceneSchedules.delete(schedule);
    }

    @Override
    @Transactional(readOnly = true)
    public List<ScheduleResponse> ruleSchedules(UUID userId, UUID homeId, UUID ruleId) {
        authorization.requireAccess(userId, homeId);
        rule(homeId, ruleId);
        return ruleSchedules.findAllByRuleIdOrderByScheduledTimeAsc(ruleId).stream()
                .map(this::response).toList();
    }

    @Override
    @Transactional
    public ScheduleResponse saveRuleSchedule(UUID userId, UUID homeId, UUID ruleId, UUID scheduleId, ScheduleRequest request) {
        authorization.requireSceneManagement(userId, homeId);
        AutomationRule rule = rule(homeId, ruleId);
        if (rule.getTriggerType() != TriggerType.SCHEDULE) throw new AppException(ErrorCode.AUTOMATION_TRIGGER_INVALID);
        Short[] days = validate(request);
        AutomationSchedule schedule = scheduleId == null ? AutomationSchedule.builder().rule(rule).build()
                : ruleSchedules.findById(scheduleId).filter(item -> item.getRule().getId().equals(ruleId))
                .orElseThrow(() -> new AppException(ErrorCode.SCENE_SCHEDULE_NOT_FOUND));
        schedule.setScheduledTime(request.getScheduledTime().withSecond(0).withNano(0));
        schedule.setRepeatDays(days);
        schedule.setActive(request.getActive());
        return response(ruleSchedules.save(schedule));
    }

    @Override
    @Transactional
    public void deleteRuleSchedule(UUID userId, UUID homeId, UUID ruleId, UUID scheduleId) {
        authorization.requireSceneManagement(userId, homeId);
        rule(homeId, ruleId);
        AutomationSchedule schedule = ruleSchedules.findById(scheduleId)
                .filter(item -> item.getRule().getId().equals(ruleId))
                .orElseThrow(() -> new AppException(ErrorCode.SCENE_SCHEDULE_NOT_FOUND));
        ruleSchedules.delete(schedule);
    }

    private Scene scene(UUID homeId, UUID sceneId) {
        return scenes.findByIdAndHomeId(sceneId, homeId)
                .orElseThrow(() -> new AppException(ErrorCode.SCENE_NOT_FOUND));
    }

    private AutomationRule rule(UUID homeId, UUID ruleId) {
        return rules.findByIdAndHomeId(ruleId, homeId)
                .orElseThrow(() -> new AppException(ErrorCode.AUTOMATION_RULE_NOT_FOUND));
    }

    private Short[] validate(ScheduleRequest request) {
        if (request == null || request.getScheduledTime() == null || request.getRepeatDays() == null
                || request.getActive() == null || request.getRepeatDays().size() > 7
                || request.getRepeatDays().stream().anyMatch(day -> day == null || day < 1 || day > 7)
                || new HashSet<>(request.getRepeatDays()).size() != request.getRepeatDays().size()) {
            throw new AppException(ErrorCode.SCENE_SCHEDULE_INVALID);
        }
        return request.getRepeatDays().toArray(Short[]::new);
    }

    private ScheduleResponse response(SceneSchedule item) {
        return build(item.getId(), item.getScheduledTime(), item.getRepeatDays(), item.isActive());
    }

    private ScheduleResponse response(AutomationSchedule item) {
        return build(item.getId(), item.getScheduledTime(), item.getRepeatDays(), item.isActive());
    }

    private ScheduleResponse build(UUID id, LocalTime time, Short[] days, boolean active) {
        return ScheduleResponse.builder().id(id).scheduledTime(time)
                .repeatDays(Arrays.asList(days)).active(active)
                .nextRunAt(active ? nextRun(time, days) : null).build();
    }

    private OffsetDateTime nextRun(LocalTime time, Short[] days) {
        ZoneId zone = ZoneId.of(zoneName);
        LocalDateTime now = LocalDateTime.now(zone);
        for (int offset = 0; offset <= 7; offset++) {
            LocalDate date = now.toLocalDate().plusDays(offset);
            LocalDateTime candidate = date.atTime(time);
            if ((days.length == 0 || Arrays.asList(days).contains((short) date.getDayOfWeek().getValue()))
                    && candidate.isAfter(now)) return candidate.atZone(zone).toOffsetDateTime();
        }
        return null;
    }
}
