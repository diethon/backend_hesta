package com.hesta.backend.service;

import com.hesta.backend.dto.response.MlRecommendationProposal;
import com.hesta.backend.entity.AutomationRecommendation;
import com.hesta.backend.entity.Device;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.Room;
import com.hesta.backend.repository.AutomationRecommendationRepository;
import com.hesta.backend.repository.DeviceRepository;
import com.hesta.backend.repository.HomeRepository;
import com.hesta.backend.repository.UserRepository;
import com.hesta.backend.service.impl.RecommendationServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/**
 * Unit tests for the deduplication and cooldown logic in RecommendationServiceImpl.
 * Finding #2 fix verification: cooldown uses resolvedAt, not createdAt.
 */
@ExtendWith(MockitoExtension.class)
class RecommendationCooldownTest {

    @Mock AutomationRecommendationRepository recommendationRepo;
    @Mock DeviceRepository deviceRepo;
    @Mock HomeRepository homeRepo;
    @Mock UserRepository userRepo;
    @Mock MlServiceClient mlServiceClient;
    @Mock BehaviorEventRecorder behaviorEventRecorder;
    @Mock AutomationRuleService ruleService;
    @Mock HomeAuthorizationService homeAuthorizationService;
    @Mock com.hesta.backend.repository.AutomationRuleRepository ruleRepository;
    @Mock com.hesta.backend.repository.AutomationScheduleRepository ruleSchedules;

    RecommendationServiceImpl service;

    UUID homeId = UUID.randomUUID();
    UUID deviceId = UUID.randomUUID();
    Home home;
    Device device;

    @BeforeEach
    void setUp() {
        service = new RecommendationServiceImpl(
                homeAuthorizationService, mlServiceClient, behaviorEventRecorder,
                recommendationRepo, deviceRepo, userRepo, homeRepo, ruleService, ruleRepository, ruleSchedules);
        ReflectionTestUtils.setField(service, "confidenceThreshold", 0.80);
        ReflectionTestUtils.setField(service, "cooldownHours", 24);

        home = Home.builder().id(homeId).name("Test Home").build();
        Room room = Room.builder().id(UUID.randomUUID()).home(home).name("Living Room").build();
        device = Device.builder().id(deviceId).name("Fan").deviceType("FAN").room(room).build();
    }

    // ── PENDING: always deduplicated regardless of action time ────────────────

    @Test
    void pendingRecommendation_blocksNewOneForSameDeviceAndAction() {
        List<AutomationRecommendation> existing = List.of(
                existingRec("PENDING", "TURN_ON", /*createdAt*/ now().minusHours(1), /*resolvedAt*/ null));

        mockMlProposal("TURN_ON", 0.90);
        mockDeviceLookup();
        when(recommendationRepo.findAllByHomeIdOrderByCreatedAtDesc(homeId)).thenReturn(existing);

        service.generateForSystem(homeId, now().minusDays(14), now());

        // No new recommendation should be saved
        verify(recommendationRepo, never()).save(any());
    }

    // ── ACCEPTED < 7 days: suppress ──────────────────────────────────────────

    @Test
    void recentlyAccepted_withinSevenDays_suppressesNewRecommendation() {
        List<AutomationRecommendation> existing = List.of(
                existingRec("ACCEPTED", "TURN_ON", now().minusDays(2), now().minusDays(2)));

        mockMlProposal("TURN_ON", 0.90);
        mockDeviceLookup();
        when(recommendationRepo.findAllByHomeIdOrderByCreatedAtDesc(homeId)).thenReturn(existing);

        service.generateForSystem(homeId, now().minusDays(14), now());

        verify(recommendationRepo, never()).save(any());
    }

    @Test
    void acceptedLongAgo_moreThanSevenDays_allowsNewRecommendation() {
        List<AutomationRecommendation> existing = List.of(
                existingRec("ACCEPTED", "TURN_ON", now().minusDays(10), now().minusDays(10)));

        mockMlProposal("TURN_ON", 0.90);
        mockDeviceLookup();
        when(recommendationRepo.findAllByHomeIdOrderByCreatedAtDesc(homeId)).thenReturn(existing);
        when(recommendationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.generateForSystem(homeId, now().minusDays(14), now());

        verify(recommendationRepo).save(any());
    }

    // ── REJECTED/DISMISSED cooldown uses resolvedAt, not createdAt ────────────

    @Test
    void rejectedRecently_cooldownBasedOnResolvedAt_suppressesNewRecommendation() {
        // Created 30 hours ago, but rejected (resolvedAt) only 2 hours ago — still in cooldown
        AutomationRecommendation old = existingRec("REJECTED", "TURN_ON", now().minusHours(30), now().minusHours(2));

        mockMlProposal("TURN_ON", 0.90);
        mockDeviceLookup();
        when(recommendationRepo.findAllByHomeIdOrderByCreatedAtDesc(homeId)).thenReturn(List.of(old));

        service.generateForSystem(homeId, now().minusDays(14), now());

        // Should NOT save — resolvedAt is within 24-hour cooldown
        verify(recommendationRepo, never()).save(any());
    }

    @Test
    void rejected36HoursAgo_isStillSuppressed() {
        // "Từ chối" is not a 24-hour snooze: a rejection stays quiet for 30 days
        AutomationRecommendation old = existingRec("REJECTED", "TURN_ON", now().minusDays(3), now().minusHours(36));

        mockMlProposal("TURN_ON", 0.90);
        mockDeviceLookup();
        when(recommendationRepo.findAllByHomeIdOrderByCreatedAtDesc(homeId)).thenReturn(List.of(old));

        service.generateForSystem(homeId, now().minusDays(14), now());

        verify(recommendationRepo, never()).save(any());
    }

    @Test
    void dismissedBeyondCooldown_allowsNewRecommendation() {
        AutomationRecommendation old = existingRec("DISMISSED", "TURN_ON", now().minusDays(3), now().minusHours(36));

        mockMlProposal("TURN_ON", 0.90);
        mockDeviceLookup();
        when(recommendationRepo.findAllByHomeIdOrderByCreatedAtDesc(homeId)).thenReturn(List.of(old));
        when(recommendationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.generateForSystem(homeId, now().minusDays(14), now());

        verify(recommendationRepo).save(any());
    }

    @Test
    void rejectedLongAgo_resolvedAtBeyondQuietPeriod_allowsNewRecommendation() {
        AutomationRecommendation old = existingRec("REJECTED", "TURN_ON", now().minusDays(40), now().minusDays(31));

        mockMlProposal("TURN_ON", 0.90);
        mockDeviceLookup();
        when(recommendationRepo.findAllByHomeIdOrderByCreatedAtDesc(homeId)).thenReturn(List.of(old));
        when(recommendationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.generateForSystem(homeId, now().minusDays(14), now());

        // Should save a new recommendation
        verify(recommendationRepo).save(any());
    }

    @Test
    void dismissedWithNoResolvedAt_fallsBackToCreatedAt_appliesCooldown() {
        // resolvedAt is null (old data), createdAt is recent — should still suppress
        AutomationRecommendation old = existingRec("DISMISSED", "TURN_ON", now().minusHours(5), null);

        mockMlProposal("TURN_ON", 0.90);
        mockDeviceLookup();
        when(recommendationRepo.findAllByHomeIdOrderByCreatedAtDesc(homeId)).thenReturn(List.of(old));

        service.generateForSystem(homeId, now().minusDays(14), now());

        verify(recommendationRepo, never()).save(any());
    }

    @Test
    void differentAction_doesNotTriggerCooldown() {
        // Existing: TURN_OFF rejected recently. New proposal is TURN_ON — different action, no cooldown.
        AutomationRecommendation old = existingRec("REJECTED", "TURN_OFF", now().minusHours(1), now().minusHours(1));

        mockMlProposal("TURN_ON", 0.90);
        mockDeviceLookup();
        when(recommendationRepo.findAllByHomeIdOrderByCreatedAtDesc(homeId)).thenReturn(List.of(old));
        when(recommendationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.generateForSystem(homeId, now().minusDays(14), now());

        verify(recommendationRepo).save(any());
    }

    // ── Confidence threshold ──────────────────────────────────────────────────

    @Test
    void lowConfidenceProposal_belowThreshold_isSkipped() {
        mockMlProposal("TURN_ON", 0.72); // below 0.80 threshold
        mockDeviceLookup();
        when(recommendationRepo.findAllByHomeIdOrderByCreatedAtDesc(homeId)).thenReturn(List.of());

        service.generateForSystem(homeId, now().minusDays(14), now());

        verify(recommendationRepo, never()).save(any());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    private OffsetDateTime now() {
        return OffsetDateTime.now();
    }

    private AutomationRecommendation existingRec(String status, String action,
                                                  OffsetDateTime createdAt, OffsetDateTime resolvedAt) {
        AutomationRecommendation rec = new AutomationRecommendation();
        rec.setId(UUID.randomUUID());
        rec.setHome(home);
        rec.setDevice(device);
        rec.setStatus(status);
        rec.setProposedAction(Map.of("action", action));
        rec.setConfidenceScore(BigDecimal.valueOf(0.85));
        rec.setTriggerCondition(Map.of("type", "SCHEDULE", "time", "07:00:00", "repeatDays", List.of()));
        // Set createdAt/resolvedAt via reflection (Hibernate-managed fields)
        ReflectionTestUtils.setField(rec, "createdAt", createdAt);
        rec.setResolvedAt(resolvedAt);
        return rec;
    }

    private void mockMlProposal(String action, double confidence) {
        MlRecommendationProposal proposal = MlRecommendationProposal.builder()
                .deviceId(deviceId)
                .action(action)
                .confidence(confidence)
                .time("07:00:00")
                .engineType("ML")
                .modelVersion("rf-v1.0.0")
                .build();
        when(mlServiceClient.generateRecommendations(eq(homeId), any(), any(), anyDouble()))
                .thenReturn(List.of(proposal));
        when(homeRepo.findById(homeId)).thenReturn(Optional.of(home));
    }

    private void mockDeviceLookup() {
        when(deviceRepo.findById(deviceId)).thenReturn(Optional.of(device));
        // device.supportsAction — default returns true (no capabilities set)
    }
}
