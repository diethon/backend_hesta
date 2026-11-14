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
import java.time.temporal.ChronoUnit;
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

    @org.springframework.beans.factory.annotation.Autowired(required = false)
    private com.hesta.backend.service.ParentalGuardianService parentalGuardianService;

    @Value("${hesta.scheduler.zone:Asia/Ho_Chi_Minh}")
    private String zoneName;

    // Max minutes replayed when a tick was delayed or skipped (claims prevent double runs).
    private static final int MAX_CATCH_UP_MINUTES = 15;
    private ZonedDateTime lastTick;

    @Scheduled(cron = "0 * * * * *", zone = "${hesta.scheduler.zone:Asia/Ho_Chi_Minh}")
    public void runDueSchedules() {
        ZonedDateTime now = ZonedDateTime.now(ZoneId.of(zoneName)).truncatedTo(ChronoUnit.MINUTES);
        ZonedDateTime from = lastTick == null ? now : lastTick.plusMinutes(1);
        if (from.isBefore(now.minusMinutes(MAX_CATCH_UP_MINUTES))) from = now.minusMinutes(MAX_CATCH_UP_MINUTES);
        for (ZonedDateTime minute = from; !minute.isAfter(now); minute = minute.plusMinutes(1)) {
            runDueSchedules(minute);
        }
        lastTick = now;
        if (parentalGuardianService != null) {
            try {
                parentalGuardianService.checkDueSessions();
            } catch (Exception ex) {
                log.error("Parental guardian due session check failed", ex);
            }
        }
    }

    void runDueSchedules(ZonedDateTime now) {
        LocalTime time = now.toLocalTime().withSecond(0).withNano(0);
        LocalDate date = now.toLocalDate();
        short day = (short) now.getDayOfWeek().getValue();
        for (SceneSchedule schedule : sceneSchedules.findAllByActiveTrueAndScheduledTime(time)) {
            if (!schedule.getScene().isEnabled() || !matchesDay(schedule.getRepeatDays(), day)
                    || !claim("SCENE", schedule.getId(), date, time)) continue;
            try {
                sceneExecutionService.executeScheduled(schedule.getScene().getHome().getId(), schedule.getScene().getId());
            } catch (RuntimeException exception) {
                log.error("Scheduled scene execution failed for schedule {}", schedule.getId(), exception);
            }
        }
        for (AutomationSchedule schedule : ruleSchedules.findAllByActiveTrueAndScheduledTime(time)) {
            if (!schedule.getRule().isEnabled() || !matchesDay(schedule.getRepeatDays(), day)
                    || !claim("RULE", schedule.getId(), date, time)) continue;
            try {
                automationEngine.executeScheduled(schedule.getRule().getHome().getId(), schedule.getRule().getId());
            } catch (RuntimeException exception) {
                log.error("Scheduled automation execution failed for schedule {}", schedule.getId(), exception);
            }
        }
    }

    // Claims are only needed for the current day; keep a week for debugging.
    @Scheduled(cron = "0 30 3 * * *", zone = "${hesta.scheduler.zone:Asia/Ho_Chi_Minh}")
    public void purgeOldClaims() {
        try {
            int deleted = jdbcTemplate.update("DELETE FROM scheduled_run_claims WHERE local_date < ?",
                    LocalDate.now(ZoneId.of(zoneName)).minusDays(7));
            log.info("Purged {} old scheduled run claims", deleted);
        } catch (RuntimeException ex) {
            log.warn("Could not purge scheduled run claims: {}", ex.getMessage());
        }
    }

    private boolean matchesDay(Short[] repeatDays, short day) {
        return repeatDays.length == 0 || Arrays.asList(repeatDays).contains(day);
    }

    private boolean claim(String type, UUID scheduleId, LocalDate date, LocalTime time) {
        // Time is part of the key so a schedule moved to a later time still runs that day.
        // shortcut: packed into schedule_type (VARCHAR 20, e.g. "SCENE@07:30") to avoid a PK migration.
        return jdbcTemplate.update("INSERT INTO scheduled_run_claims (schedule_type, schedule_id, local_date) "
                + "VALUES (?, ?, ?) ON CONFLICT DO NOTHING", type + "@" + time, scheduleId, date) == 1;
    }
}
