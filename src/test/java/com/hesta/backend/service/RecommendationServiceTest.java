package com.hesta.backend.service;

import com.hesta.backend.dto.request.CreateAutomationRuleRequest;
import com.hesta.backend.dto.request.ScheduleRequest;
import com.hesta.backend.dto.response.AutomationRuleResponse;
import com.hesta.backend.dto.response.MlModelStatusResponse;
import com.hesta.backend.dto.response.MlRecommendationProposal;
import com.hesta.backend.dto.response.RecommendationResponse;
import com.hesta.backend.entity.AutomationRecommendation;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.EdgeNode;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Room;
import com.hesta.backend.entity.User;
import com.hesta.backend.repository.AutomationRecommendationRepository;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.HomeRepository;
import com.hesta.backend.repository.UserRepository;
import com.hesta.backend.service.impl.RecommendationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

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
    @Mock MlServiceClient mlServiceClient;
    @Mock BehaviorEventRecorder behaviorEventRecorder;
    @Mock AutomationRecommendationRepository recommendations;
    @Mock DeviceRepository devices;
    @Mock UserRepository users;
    @Mock HomeRepository homeRepository;
    @Mock AutomationRuleService rules;
    @Mock ScheduleService schedules;
    @InjectMocks RecommendationServiceImpl service;

    private final UUID userId = UUID.randomUUID();
    private final UUID homeId = UUID.randomUUID();
    private final UUID deviceId = UUID.randomUUID();
    private final UUID recommendationId = UUID.randomUUID();
    private final Home home = Home.builder().id(homeId).build();
    private final Room room = Room.builder().id(UUID.randomUUID()).home(home).name("Living room").build();
    private final Device device = Device.builder().id(deviceId).node(EdgeNode.builder().home(home).build())
            .room(room).name("Living room light")
            .capabilities(Map.of("power", List.of("TURN_ON", "TURN_OFF")))
            .build();

    @BeforeEach
    void setUp() {
        ReflectionTestUtils.setField(service, "confidenceThreshold", 0.80);
        ReflectionTestUtils.setField(service, "cooldownHours", 24);
    }

    @Test
    void generatesForSystemWithoutRequiringUserSession() {
        when(homeRepository.findById(homeId)).thenReturn(Optional.of(home));
        when(recommendations.findAllByHomeIdOrderByCreatedAtDesc(homeId)).thenReturn(List.of());
        when(devices.findById(deviceId)).thenReturn(Optional.of(device));

        OffsetDateTime to = OffsetDateTime.now();
        OffsetDateTime from = to.minusDays(14);

        when(mlServiceClient.generateRecommendations(eq(homeId), eq(from), eq(to), anyDouble())).thenReturn(List.of(
                MlRecommendationProposal.builder()
                        .deviceId(deviceId)
                        .deviceName(device.getName())
                        .action("TURN_ON")
                        .time("06:30:00")
                        .confidence(0.91)
                        .eligible(true)
                        .reason("Background habit detection")
                        .engineType("ML")
                        .modelVersion("rf-v1.0.0")
                        .build()
        ));

        when(recommendations.save(any())).thenAnswer(invocation -> {
            AutomationRecommendation item = invocation.getArgument(0);
            item.setId(recommendationId);
            return item;
        });

        List<RecommendationResponse> result = service.generateForSystem(homeId, from, to);

        assertThat(result).hasSize(1);
        assertThat(result.get(0).getConfidence()).isEqualTo(0.91);
        assertThat(result.get(0).getRoomName()).isEqualTo("Living room");
        verify(recommendations).save(any(AutomationRecommendation.class));
    }

    @Test
    void skipsProposalsBelowConfidenceThreshold() {
        when(homeRepository.findById(homeId)).thenReturn(Optional.of(home));
        when(recommendations.findAllByHomeIdOrderByCreatedAtDesc(homeId)).thenReturn(List.of());
        when(devices.findById(deviceId)).thenReturn(Optional.of(device));

        OffsetDateTime to = OffsetDateTime.now();
        OffsetDateTime from = to.minusDays(14);

        when(mlServiceClient.generateRecommendations(eq(homeId), eq(from), eq(to), anyDouble())).thenReturn(List.of(
                MlRecommendationProposal.builder()
                        .deviceId(deviceId)
                        .deviceName(device.getName())
                        .action("TURN_ON")
                        .time("06:30:00")
                        .confidence(0.72) // Below threshold (0.80)
                        .eligible(false)
                        .reason("Uncertain habit")
                        .engineType("ML")
                        .modelVersion("rf-v1.0.0")
                        .build()
        ));

        List<RecommendationResponse> result = service.generateForSystem(homeId, from, to);

        assertThat(result).isEmpty();
        verify(recommendations, never()).save(any());
    }

    @Test
    void skipsDuplicatePendingRecommendationForSameDeviceAndAction() {
        when(homeRepository.findById(homeId)).thenReturn(Optional.of(home));
        when(recommendations.findAllByHomeIdOrderByCreatedAtDesc(homeId)).thenReturn(List.of(pending()));
        when(devices.findById(deviceId)).thenReturn(Optional.of(device));

        OffsetDateTime to = OffsetDateTime.now();
        OffsetDateTime from = to.minusDays(14);

        when(mlServiceClient.generateRecommendations(eq(homeId), eq(from), eq(to), anyDouble())).thenReturn(List.of(
                MlRecommendationProposal.builder()
                        .deviceId(deviceId)
                        .deviceName(device.getName())
                        .action("TURN_ON")
                        .time("07:15:00")
                        .confidence(0.92)
                        .eligible(true)
                        .reason("Duplicate attempt")
                        .engineType("ML")
                        .modelVersion("rf-v1.0.0")
                        .build()
        ));

        List<RecommendationResponse> result = service.generateForSystem(homeId, from, to);

        assertThat(result).isEmpty();
        verify(recommendations, never()).save(any());
    }

    @Test
    void skipsProposalsForUnsupportedDeviceActions() {
        when(homeRepository.findById(homeId)).thenReturn(Optional.of(home));
        when(recommendations.findAllByHomeIdOrderByCreatedAtDesc(homeId)).thenReturn(List.of());

        // Device only has TURN_ON, TURN_OFF capabilities; propose unsupported GATE_OPEN
        when(devices.findById(deviceId)).thenReturn(Optional.of(device));

        OffsetDateTime to = OffsetDateTime.now();
        OffsetDateTime from = to.minusDays(14);

        when(mlServiceClient.generateRecommendations(eq(homeId), eq(from), eq(to), anyDouble())).thenReturn(List.of(
                MlRecommendationProposal.builder()
                        .deviceId(deviceId)
                        .deviceName(device.getName())
                        .action("GATE_OPEN") // Invalid action for light
                        .time("07:15:00")
                        .confidence(0.95)
                        .eligible(true)
                        .engineType("ML")
                        .modelVersion("rf-v1.0.0")
                        .build()
        ));

        List<RecommendationResponse> result = service.generateForSystem(homeId, from, to);

        assertThat(result).isEmpty();
        verify(recommendations, never()).save(any());
    }

    @Test
    void getTopRecommendationReturnsHighestConfidencePending() {
        when(recommendations.findFirstByHomeIdAndStatusOrderByConfidenceScoreDescCreatedAtDesc(homeId, "PENDING"))
                .thenReturn(Optional.of(pending()));

        RecommendationResponse top = service.getTopRecommendation(userId, homeId);

        assertThat(top).isNotNull();
        assertThat(top.getId()).isEqualTo(recommendationId);
        assertThat(top.getConfidence()).isEqualTo(0.88);
        assertThat(top.getRoomName()).isEqualTo("Living room");
        verify(authorization).requireAccess(userId, homeId);
    }

    @Test
    void getTopRecommendationReturnsNullWhenNonePending() {
        when(recommendations.findFirstByHomeIdAndStatusOrderByConfidenceScoreDescCreatedAtDesc(homeId, "PENDING"))
                .thenReturn(Optional.empty());

        RecommendationResponse top = service.getTopRecommendation(userId, homeId);

        assertThat(top).isNull();
        verify(authorization).requireAccess(userId, homeId);
    }

    @Test
    void getTopRecommendationReturnsNullWhenBelowThreshold() {
        AutomationRecommendation lowConfPending = pending();
        lowConfPending.setConfidenceScore(BigDecimal.valueOf(0.75));

        when(recommendations.findFirstByHomeIdAndStatusOrderByConfidenceScoreDescCreatedAtDesc(homeId, "PENDING"))
                .thenReturn(Optional.of(lowConfPending));

        RecommendationResponse top = service.getTopRecommendation(userId, homeId);

        assertThat(top).isNull();
        verify(authorization).requireAccess(userId, homeId);
    }

    @Test
    void approvalCreatesRuleAndScheduleAndRecordsFeedback() {
        when(recommendations.findByIdAndHomeId(recommendationId, homeId)).thenReturn(Optional.of(pending()));
        when(users.findById(userId)).thenReturn(Optional.of(User.builder().id(userId).build()));
        UUID ruleId = UUID.randomUUID();
        when(rules.create(eq(userId), eq(homeId), any())).thenReturn(AutomationRuleResponse.builder().id(ruleId).build());
        when(recommendations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.approve(userId, homeId, recommendationId);

        assertThat(result.getStatus()).isEqualTo("ACCEPTED");
        verify(rules).create(eq(userId), eq(homeId), any(CreateAutomationRuleRequest.class));

        ArgumentCaptor<ScheduleRequest> scheduleCaptor = ArgumentCaptor.forClass(ScheduleRequest.class);
        verify(schedules).saveRuleSchedule(eq(userId), eq(homeId), eq(ruleId), isNull(), scheduleCaptor.capture());
        assertThat(scheduleCaptor.getValue().getScheduledTime()).isEqualTo(LocalTime.of(7, 15));

        // Verify feedback recorded
        verify(behaviorEventRecorder).recordRecommendationFeedback(
                eq(home), eq(userId), eq(device), eq(recommendationId), eq("ACCEPTED"), eq("ML"), eq("rf-v1.0.0")
        );
    }

    @Test
    void rejectionRecordsFeedbackAndNeverCreatesRule() {
        when(recommendations.findByIdAndHomeId(recommendationId, homeId)).thenReturn(Optional.of(pending()));
        when(users.findById(userId)).thenReturn(Optional.of(User.builder().id(userId).build()));
        when(recommendations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.reject(userId, homeId, recommendationId);

        assertThat(result.getStatus()).isEqualTo("REJECTED");
        verifyNoInteractions(rules, schedules);
        verify(behaviorEventRecorder).recordRecommendationFeedback(
                eq(home), eq(userId), eq(device), eq(recommendationId), eq("REJECTED"), eq("ML"), eq("rf-v1.0.0")
        );
    }

    @Test
    void dismissRecordsFeedbackAndTransitionsStatus() {
        when(recommendations.findByIdAndHomeId(recommendationId, homeId)).thenReturn(Optional.of(pending()));
        when(users.findById(userId)).thenReturn(Optional.of(User.builder().id(userId).build()));
        when(recommendations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var result = service.dismiss(userId, homeId, recommendationId);

        assertThat(result.getStatus()).isEqualTo("DISMISSED");
        verifyNoInteractions(rules, schedules);
        verify(behaviorEventRecorder).recordRecommendationFeedback(
                eq(home), eq(userId), eq(device), eq(recommendationId), eq("DISMISSED"), eq("ML"), eq("rf-v1.0.0")
        );
    }

    @Test
    void getModelStatusDelegatesToMlClient() {
        when(mlServiceClient.getModelStatus()).thenReturn(MlModelStatusResponse.builder()
                .status("READY")
                .version("rf-v1.0.0")
                .accuracy(0.85)
                .confidenceThreshold(0.80)
                .build());

        var status = service.getModelStatus(userId, homeId);

        assertThat(status.getStatus()).isEqualTo("READY");
        assertThat(status.getVersion()).isEqualTo("rf-v1.0.0");
        assertThat(status.getConfidenceThreshold()).isEqualTo(0.80);
        verify(authorization).requireAccess(userId, homeId);
    }

    private AutomationRecommendation pending() {
        return AutomationRecommendation.builder()
                .id(recommendationId)
                .home(home)
                .device(device)
                .status("PENDING")
                .engineType("ML")
                .modelVersion("rf-v1.0.0")
                .confidenceScore(BigDecimal.valueOf(0.88))
                .triggerCondition(Map.of("type", "SCHEDULE", "time", "07:15", "repeatDays", List.of()))
                .proposedAction(Map.of("deviceId", deviceId.toString(), "action", "TURN_ON", "parameters", Map.of()))
                .build();
    }
}
