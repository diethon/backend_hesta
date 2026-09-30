package com.hesta.backend.service.impl;

import com.hesta.backend.entity.AutomationSchedule;
import com.hesta.backend.entity.SceneSchedule;
import com.hesta.backend.repository.AutomationScheduleRepository;
import com.hesta.backend.repository.SceneScheduleRepository;
import com.hesta.backend.service.AutomationEngine;
import com.hesta.backend.service.SceneExecutionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.UUID;

@Component
@ConditionalOnProperty(name = "hesta.scheduler.enabled", havingValue = "true", matchIfMissing = true)
@Slf4j
@RequiredArgsConstructor
public class ScheduleRunner {
    private final SceneScheduleRepository sceneSchedules;
    private final AutomationScheduleRepository ruleSchedules;
    private final SceneExecutionService sceneExecutionService;
    private final AutomationEngine automationEngine;
    private final JdbcTemplate jdbcTemplate;

    @Value("${hesta.scheduler.zone:Asia/Ho_Chi_Minh}")
    private String zoneName;

    @Scheduled(cron = "0 * * * * *", zone = "${hesta.scheduler.zone:Asia/Ho_Chi_Minh}")
    public void runDueSchedules() {
        runDueSchedules(ZonedDateTime.now(ZoneId.of(zoneName)));
    }

    void runDueSchedules(ZonedDateTime now) {
        LocalTime time = now.toLocalTime().withSecond(0).withNano(0);
        LocalDate date = now.toLocalDate();
        short day = (short) now.getDayOfWeek().getValue();
        for (SceneSchedule schedule : sceneSchedules.findAllByActiveTrueAndScheduledTime(time)) {
            if (!schedule.getScene().isEnabled() || !matchesDay(schedule.getRepeatDays(), day)
                    || !claim("SCENE", schedule.getId(), date)) continue;
            try {
                sceneExecutionService.executeScheduled(schedule.getScene().getHome().getId(), schedule.getScene().getId());
            } catch (RuntimeException exception) {
                log.error("Scheduled scene execution failed for schedule {}", schedule.getId(), exception);
            }
        }
        for (AutomationSchedule schedule : ruleSchedules.findAllByActiveTrueAndScheduledTime(time)) {
            if (!schedule.getRule().isEnabled() || !matchesDay(schedule.getRepeatDays(), day)
                    || !claim("RULE", schedule.getId(), date)) continue;
            try {
                automationEngine.executeScheduled(schedule.getRule().getHome().getId(), schedule.getRule().getId());
            } catch (RuntimeException exception) {
                log.error("Scheduled automation execution failed for schedule {}", schedule.getId(), exception);
            }
        }
    }

    private boolean matchesDay(Short[] repeatDays, short day) {
        return repeatDays.length == 0 || Arrays.asList(repeatDays).contains(day);
    }

    private boolean claim(String type, UUID scheduleId, LocalDate date) {
        return jdbcTemplate.update("INSERT INTO scheduled_run_claims (schedule_type, schedule_id, local_date) "
                + "VALUES (?, ?, ?) ON CONFLICT DO NOTHING", type, scheduleId, date) == 1;
    }
}
