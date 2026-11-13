package com.hesta.backend.service;

import com.hesta.backend.dto.request.ScheduleRequest;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Scene;
import com.hesta.backend.entity.SceneSchedule;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.repository.AutomationRuleRepository;
import com.hesta.backend.repository.AutomationScheduleRepository;
import com.hesta.backend.repository.SceneRepository;
import com.hesta.backend.repository.SceneScheduleRepository;
import com.hesta.backend.service.impl.ScheduleServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ScheduleServiceTest {
    @Mock HomeAuthorizationService authorization;
    @Mock SceneRepository scenes;
    @Mock SceneScheduleRepository sceneSchedules;
    @Mock AutomationRuleRepository rules;
    @Mock AutomationScheduleRepository ruleSchedules;
    @InjectMocks ScheduleServiceImpl service;

    UUID userId = UUID.randomUUID();
    UUID homeId = UUID.randomUUID();
    UUID sceneId = UUID.randomUUID();

    @BeforeEach
    void zone() {
        ReflectionTestUtils.setField(service, "zoneName", "Asia/Ho_Chi_Minh");
    }

    @Test
    void savesWeeklyScheduleWithNextRun() {
        Scene scene = Scene.builder().id(sceneId).home(Home.builder().id(homeId).build()).build();
        when(scenes.findByIdAndHomeId(sceneId, homeId)).thenReturn(Optional.of(scene));
        when(sceneSchedules.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        ScheduleRequest request = request(List.of((short) 1, (short) 3));

        var response = service.saveSceneSchedule(userId, homeId, sceneId, null, request);

        assertThat(response.getScheduledTime()).isEqualTo(LocalTime.of(7, 0));
        assertThat(response.getRepeatDays()).containsExactly((short) 1, (short) 3);
        assertThat(response.getNextRunAt()).isNotNull();
        verify(authorization).requireSceneManagement(userId, homeId);
    }

    @Test
    void rejectsDuplicateOrOutOfRangeWeekdays() {
        Scene scene = Scene.builder().id(sceneId).build();
        when(scenes.findByIdAndHomeId(sceneId, homeId)).thenReturn(Optional.of(scene));
        assertThatThrownBy(() -> service.saveSceneSchedule(userId, homeId, sceneId, null,
                request(List.of((short) 1, (short) 1)))).isInstanceOf(AppException.class);
        assertThatThrownBy(() -> service.saveSceneSchedule(userId, homeId, sceneId, null,
                request(List.of((short) 8)))).isInstanceOf(AppException.class);
        verifyNoInteractions(sceneSchedules);
    }

    @Test
    void lastActiveTimeOfARuleCannotBeRemovedOrPaused() {
        UUID ruleId = UUID.randomUUID();
        var rule = com.hesta.backend.entity.AutomationRule.builder().id(ruleId)
                .triggerType(com.hesta.backend.enums.TriggerType.SCHEDULE).build();
        var only = com.hesta.backend.entity.AutomationSchedule.builder().id(UUID.randomUUID()).rule(rule)
                .scheduledTime(LocalTime.of(7, 0)).active(true).build();
        when(rules.findByIdAndHomeId(ruleId, homeId)).thenReturn(Optional.of(rule));
        when(ruleSchedules.findById(only.getId())).thenReturn(Optional.of(only));
        when(ruleSchedules.countByRuleIdAndActiveTrue(ruleId)).thenReturn(1L);
        ScheduleRequest pause = request(List.of());
        pause.setActive(false);

        assertThatThrownBy(() -> service.deleteRuleSchedule(userId, homeId, ruleId, only.getId()))
                .extracting("errorCode").isEqualTo(com.hesta.backend.exception.ErrorCode.AUTOMATION_SCHEDULE_REQUIRED);
        assertThatThrownBy(() -> service.saveRuleSchedule(userId, homeId, ruleId, only.getId(), pause))
                .extracting("errorCode").isEqualTo(com.hesta.backend.exception.ErrorCode.AUTOMATION_SCHEDULE_REQUIRED);
        verify(ruleSchedules, never()).delete(any());
        verify(ruleSchedules, never()).save(any());

        when(ruleSchedules.countByRuleIdAndActiveTrue(ruleId)).thenReturn(2L);
        service.deleteRuleSchedule(userId, homeId, ruleId, only.getId());
        verify(ruleSchedules).delete(only);
    }

    @Test
    void ruleTimesAreSyncedToTheSubmittedList() {
        var rule = com.hesta.backend.entity.AutomationRule.builder().id(UUID.randomUUID())
                .triggerType(com.hesta.backend.enums.TriggerType.SCHEDULE).build();
        var kept = com.hesta.backend.entity.AutomationSchedule.builder().id(UUID.randomUUID()).rule(rule)
                .scheduledTime(LocalTime.of(7, 0)).active(true).build();
        var removed = com.hesta.backend.entity.AutomationSchedule.builder().id(UUID.randomUUID()).rule(rule)
                .scheduledTime(LocalTime.of(9, 0)).active(true).build();
        when(ruleSchedules.findAllByRuleIdOrderByScheduledTimeAsc(rule.getId())).thenReturn(List.of(kept, removed));
        ScheduleRequest moved = request(List.of((short) 6));
        moved.setId(kept.getId());
        moved.setScheduledTime(LocalTime.of(8, 30));
        ScheduleRequest added = request(List.of());

        service.syncRuleSchedules(rule, List.of(moved, added));

        assertThat(kept.getScheduledTime()).isEqualTo(LocalTime.of(8, 30));
        assertThat(kept.getRepeatDays()).containsExactly((short) 6);
        verify(ruleSchedules).save(kept);
        verify(ruleSchedules).save(argThat(item -> item.getId() == null && item.getScheduledTime().equals(LocalTime.of(7, 0))));
        verify(ruleSchedules).delete(removed);
        verify(ruleSchedules, never()).delete(kept);
    }

    @Test
    void syncRejectsATimeOfAnotherRule() {
        var rule = com.hesta.backend.entity.AutomationRule.builder().id(UUID.randomUUID())
                .triggerType(com.hesta.backend.enums.TriggerType.SCHEDULE).build();
        when(ruleSchedules.findAllByRuleIdOrderByScheduledTimeAsc(rule.getId())).thenReturn(List.of());
        ScheduleRequest foreign = request(List.of());
        foreign.setId(UUID.randomUUID());

        assertThatThrownBy(() -> service.syncRuleSchedules(rule, List.of(foreign))).isInstanceOf(AppException.class);
        verify(ruleSchedules, never()).delete(any());
    }

    private ScheduleRequest request(List<Short> days) {
        ScheduleRequest request = new ScheduleRequest();
        request.setScheduledTime(LocalTime.of(7, 0));
        request.setRepeatDays(days);
        request.setActive(true);
        return request;
    }
}
