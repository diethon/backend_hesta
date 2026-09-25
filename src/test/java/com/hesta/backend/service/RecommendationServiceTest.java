package com.hesta.backend.service;

import com.hesta.backend.dto.request.CreateAutomationRuleRequest;
import com.hesta.backend.dto.request.ScheduleRequest;
import com.hesta.backend.dto.response.AutomationRuleResponse;
import com.hesta.backend.dto.response.BehaviorPatternResponse;
import com.hesta.backend.dto.response.BehaviorPredictionResponse;
import com.hesta.backend.entity.AutomationRecommendation;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.EdgeNode;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Room;
import com.hesta.backend.entity.User;
import com.hesta.backend.repository.AutomationRecommendationRepository;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.UserRepository;
import com.hesta.backend.service.impl.RecommendationServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class RecommendationServiceTest {
    @Mock HomeAuthorizationService authorization;
    @Mock BehaviorService behavior;
    @Mock AutomationRecommendationRepository recommendations;
    @Mock DeviceRepository devices;
    @Mock UserRepository users;
    @Mock AutomationRuleService rules;
    @Mock ScheduleService schedules;
    @InjectMocks RecommendationServiceImpl service;

    private final UUID userId = UUID.randomUUID();
    private final UUID homeId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();
    private final UUID recommendationId = UUID.randomUUID();
    private final Home home = Home.builder().id(homeId).build();
    private final Device device = Device.builder().id(deviceId).node(EdgeNode.builder().home(home).build())
            .room(Room.builder().home(home).build()).name("Living room light").build();

    @Test
    void generatesOnlyReliableSupportedPatterns() {
        when(authorization.requireSceneManagement(userId, homeId)).thenReturn(home);
        when(recommendations.findAllByHomeIdOrderByCreatedAtDesc(homeId)).thenReturn(List.of());
        when(devices.findById(deviceId)).thenReturn(Optional.of(device));
        OffsetDateTime to = OffsetDateTime.now();
        OffsetDateTime from = to.minusDays(7);
        when(behavior.detectPatterns(userId, homeId, from, to)).thenReturn(List.of(
                BehaviorPatternResponse.builder().deviceId(deviceId).patternType("MORNING").action("TURN_ON")
                        .averageTime(LocalTime.of(7, 15)).occurrences(6).confidence(0.85).build(),
                BehaviorPatternResponse.builder().deviceId(deviceId).patternType("BEDTIME").action("TURN_OFF")
                        .averageTime(LocalTime.of(21, 0)).occurrences(1).confidence(0.2).build()));
        when(recommendations.save(any())).thenAnswer(invocation -> {
            AutomationRecommendation item = invocation.getArgument(0);
            item.setId(recommendationId);
            return item;
        });

        var result = service.generate(userId, homeId, from, to);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getStatus()).isEqualTo("PENDING");
        assertThat(result.getFirst().getTriggerCondition().get("time")).isEqualTo("07:15");
        verify(recommendations, times(1)).save(any());
        verifyNoInteractions(rules, schedules);
    }

    @Test
    void predictionProducesPendingRecommendationWithoutExecutingRule() {
        when(authorization.requireSceneManagement(userId, homeId)).thenReturn(home);
        when(recommendations.findAllByHomeIdOrderByCreatedAtDesc(homeId)).thenReturn(List.of());
        when(devices.findById(deviceId)).thenReturn(Optional.of(device));
        OffsetDateTime to = OffsetDateTime.now();
        OffsetDateTime from = to.minusDays(7);
        when(behavior.predict(eq(userId), eq(homeId), eq(from), eq(to), any())).thenReturn(List.of(
                BehaviorPredictionResponse.builder().deviceId(deviceId).action("TURN_ON")
                        .predictedTime(LocalTime.of(7, 15)).confidence(0.85).reason("Repeated").build()));
        when(recommendations.save(any())).thenAnswer(invocation -> {
            AutomationRecommendation item = invocation.getArgument(0);
            item.setId(recommendationId);
            return item;
        });

        var result = service.generate(userId, homeId, from, to);

        assertThat(result).hasSize(1);
        assertThat(result.getFirst().getTriggerCondition().get("patternType")).isEqualTo("PREDICTION");
        verifyNoInteractions(rules, schedules);
    }

    @Test
    void approvalCreatesOneEnabledScheduledRule() {
        AutomationRecommendation item = pending();
        UUID ruleId = UUID.randomUUID();
        when(recommendations.findByIdAndHomeId(recommendationId, homeId)).thenReturn(Optional.of(item));
        when(users.findById(userId)).thenReturn(Optional.of(User.builder().id(userId).build()));
        when(rules.create(eq(userId), eq(homeId), any())).thenReturn(AutomationRuleResponse.builder().id(ruleId).build());
        when(recommendations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.approve(userId, homeId, recommendationId);

        assertThat(result.getStatus()).isEqualTo("APPROVED");
        ArgumentCaptor<CreateAutomationRuleRequest> rule = ArgumentCaptor.forClass(CreateAutomationRuleRequest.class);
        verify(rules).create(eq(userId), eq(homeId), rule.capture());
        assertThat(rule.getValue().getTriggerType()).isEqualTo("SCHEDULE");
        assertThat(rule.getValue().getActions().getFirst().getDeviceId()).isEqualTo(deviceId);
        ArgumentCaptor<ScheduleRequest> schedule = ArgumentCaptor.forClass(ScheduleRequest.class);
        verify(schedules).saveRuleSchedule(eq(userId), eq(homeId), eq(ruleId), isNull(), schedule.capture());
        assertThat(schedule.getValue().getScheduledTime()).isEqualTo(LocalTime.of(7, 15));
    }

    @Test
    void rejectionNeverCreatesARule() {
        when(recommendations.findByIdAndHomeId(recommendationId, homeId)).thenReturn(Optional.of(pending()));
        when(users.findById(userId)).thenReturn(Optional.of(User.builder().id(userId).build()));
        when(recommendations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));
        var result = service.reject(userId, homeId, recommendationId);
        assertThat(result.getStatus()).isEqualTo("REJECTED");
        verifyNoInteractions(rules, schedules);
    }

    private AutomationRecommendation pending() {
        return AutomationRecommendation.builder().id(recommendationId).home(home).device(device)
                .status("PENDING").confidenceScore(BigDecimal.valueOf(0.8))
                .triggerCondition(Map.of("type", "SCHEDULE", "time", "07:15", "repeatDays", List.of()))
                .proposedAction(Map.of("deviceId", deviceId.toString(), "action", "TURN_ON", "parameters", Map.of()))
                .build();
    }
}
