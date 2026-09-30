package com.hesta.backend.service.impl;

import com.hesta.backend.entity.AutomationRule;
import com.hesta.backend.entity.AutomationSchedule;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Scene;
import com.hesta.backend.entity.SceneSchedule;
import com.hesta.backend.repository.AutomationScheduleRepository;
import com.hesta.backend.repository.SceneScheduleRepository;
import com.hesta.backend.service.AutomationEngine;
import com.hesta.backend.service.SceneExecutionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.LocalTime;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduleRunnerTest {
    @Mock SceneScheduleRepository scenes;
    @Mock AutomationScheduleRepository rules;
    @Mock SceneExecutionService sceneExecution;
    @Mock AutomationEngine automationEngine;
    @Mock JdbcTemplate jdbc;
    @InjectMocks ScheduleRunner runner;

    @Test
    void executesMatchingSchedulesOnlyAfterPersistentClaim() {
        ZonedDateTime now = ZonedDateTime.parse("2026-09-22T07:30:00+07:00[Asia/Ho_Chi_Minh]");
        UUID homeId = UUID.randomUUID();
        Scene scene = Scene.builder().id(UUID.randomUUID()).home(Home.builder().id(homeId).build()).enabled(true).build();
        SceneSchedule sceneSchedule = SceneSchedule.builder().id(UUID.randomUUID()).scene(scene)
                .scheduledTime(LocalTime.of(7, 30)).repeatDays(new Short[]{2}).active(true).build();
        AutomationRule rule = AutomationRule.builder().id(UUID.randomUUID()).home(scene.getHome()).enabled(false).build();
        AutomationSchedule disabled = AutomationSchedule.builder().id(UUID.randomUUID()).rule(rule)
                .scheduledTime(LocalTime.of(7, 30)).repeatDays(new Short[0]).active(true).build();
        when(scenes.findAllByActiveTrueAndScheduledTime(LocalTime.of(7, 30))).thenReturn(List.of(sceneSchedule));
        when(rules.findAllByActiveTrueAndScheduledTime(LocalTime.of(7, 30))).thenReturn(List.of(disabled));
        when(jdbc.update(anyString(), eq("SCENE"), eq(sceneSchedule.getId()), eq(now.toLocalDate()))).thenReturn(1);

        runner.runDueSchedules(now);

        verify(sceneExecution).executeScheduled(homeId, scene.getId());
        verifyNoInteractions(automationEngine);
        verify(jdbc, times(1)).update(anyString(), any(), any(), any());
    }

    @Test
    void skipsWrongDayAndDuplicateClaim() {
        ZonedDateTime now = ZonedDateTime.parse("2026-09-22T07:30:00+07:00[Asia/Ho_Chi_Minh]");
        Scene scene = Scene.builder().id(UUID.randomUUID()).home(Home.builder().id(UUID.randomUUID()).build()).enabled(true).build();
        SceneSchedule wrongDay = SceneSchedule.builder().id(UUID.randomUUID()).scene(scene)
                .repeatDays(new Short[]{1}).build();
        SceneSchedule duplicate = SceneSchedule.builder().id(UUID.randomUUID()).scene(scene)
                .repeatDays(new Short[]{2}).build();
        when(scenes.findAllByActiveTrueAndScheduledTime(LocalTime.of(7, 30))).thenReturn(List.of(wrongDay, duplicate));
        when(jdbc.update(anyString(), eq("SCENE"), eq(duplicate.getId()), eq(now.toLocalDate()))).thenReturn(0);

        runner.runDueSchedules(now);

        verifyNoInteractions(sceneExecution, automationEngine);
        verify(jdbc, times(1)).update(anyString(), any(), any(), any());
    }
}
