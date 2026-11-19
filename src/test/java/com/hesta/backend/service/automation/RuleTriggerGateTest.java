package com.hesta.backend.service.automation;

import com.hesta.backend.dto.request.AutomationEventRequest;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class RuleTriggerGateTest {
    UUID ruleId = UUID.randomUUID();
    AutomationEventRequest report = AutomationEventRequest.builder().sourceDeviceId(UUID.randomUUID())
            .eventType("SENSOR").data(Map.of()).stateReport(true).build();

    @Test
    void matchHeldBackByCooldownFiresOnALaterReport() {
        RuleTriggerGate gate = new RuleTriggerGate(3600);
        gate.fired(ruleId); // in cooldown
        assertThat(gate.shouldFire(ruleId, report, true, false)).isFalse();
        gate.forget(ruleId); // cooldown over (forget clears it too)
        assertThat(gate.shouldFire(ruleId, report, true, false)).isTrue();
    }

    @Test
    void handledMatchWaitsForTheNextEdge() {
        RuleTriggerGate gate = new RuleTriggerGate(0);
        assertThat(gate.shouldFire(ruleId, report, true, false)).isTrue();
        gate.handled(ruleId, report);
        assertThat(gate.shouldFire(ruleId, report, true, false)).isFalse();
        assertThat(gate.shouldFire(ruleId, report, false, false)).isFalse();
        assertThat(gate.shouldFire(ruleId, report, true, false)).isTrue();
    }

    @Test
    void concurrentReportsOfOneRuleRunItOnce() throws Exception {
        RuleTriggerGate gate = new RuleTriggerGate(0);
        int threads = 8;
        var start = new java.util.concurrent.CountDownLatch(1);
        var claimed = new java.util.concurrent.atomic.AtomicInteger();
        var pool = java.util.concurrent.Executors.newFixedThreadPool(threads);
        for (int i = 0; i < threads; i++) {
            pool.submit(() -> {
                start.await();
                if (gate.tryClaim(ruleId, report, true, false)) claimed.incrementAndGet();
                return null;
            });
        }
        start.countDown();
        pool.shutdown();
        assertThat(pool.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
        assertThat(claimed.get()).isEqualTo(1);
    }

    @Test
    void testEventsDoNotConsumeTheEdge() {
        RuleTriggerGate gate = new RuleTriggerGate(3600);
        AutomationEventRequest test = AutomationEventRequest.builder().sourceDeviceId(report.getSourceDeviceId())
                .eventType("SENSOR").data(Map.of()).stateReport(true).test(true).build();
        assertThat(gate.tryClaim(ruleId, test, true, false)).isTrue();
        assertThat(gate.tryClaim(ruleId, report, true, false)).isTrue();
    }
}
