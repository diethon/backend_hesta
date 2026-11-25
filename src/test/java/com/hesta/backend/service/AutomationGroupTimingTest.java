package com.hesta.backend.service;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.request.AutomationEventRequest;
import com.hesta.backend.entity.*;
import com.hesta.backend.enums.*;
import com.hesta.backend.repository.*;
import com.hesta.backend.service.impl.AutomationEngineImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import java.util.*;
import java.util.concurrent.AbstractExecutorService;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Group targets/conditions, DELAY steps, auto-off and "true for X seconds" holds. */
@ExtendWith(MockitoExtension.class)
class AutomationGroupTimingTest {
    @Mock AutomationRuleRepository ruleRepository;
    @Mock AutomationExecutionRepository executionRepository;
    @Mock DeviceRepository deviceRepository;
    @Mock DeviceCommandService commandService;
    @Mock BehaviorEventRecorder behaviorEventRecorder;
    @Mock ManualOverrideService manualOverrideService;
    @Mock SceneExecutionService sceneExecutionService;
    AutomationEngineImpl engine;
    final ManualExecutor background = new ManualExecutor();
    final List<Long> sleeps = new ArrayList<>();
    final Home home = Home.builder().id(UUID.randomUUID()).name("Home").build();
    Device sensor;
    Device fan;
    Device lamp;
    AutomationRule rule;
    final Map<UUID, AutomationExecution> saved = new HashMap<>();

    @BeforeEach
    void setUp() throws Exception {
        engine = new AutomationEngineImpl(ruleRepository, executionRepository, deviceRepository, behaviorEventRecorder,
                manualOverrideService, sceneExecutionService, new com.hesta.backend.service.automation.RuleMatcher("Asia/Ho_Chi_Minh"),
                new com.hesta.backend.service.automation.RuleTriggerGate(0),
                new com.hesta.backend.service.execution.CommandBatchRunner(commandService, behaviorEventRecorder));
        ReflectionTestUtils.setField(engine, "background", background);
        Class<?> sleeperType = Class.forName("com.hesta.backend.service.impl.AutomationEngineImpl$Sleeper");
        ReflectionTestUtils.setField(engine, "sleeper", java.lang.reflect.Proxy.newProxyInstance(
                sleeperType.getClassLoader(), new Class<?>[]{sleeperType}, (proxy, method, args) -> {
                    sleeps.add((Long) args[0]);
                    return null;
                }));
        sensor = device("Temp", Map.of("sensor", List.of("GET_STATE")));
        fan = device("Fan", Map.of());
        lamp = device("Lamp", Map.of());
        rule = AutomationRule.builder().id(UUID.randomUUID()).home(home).name("Hot").enabled(true)
                .triggerType(TriggerType.SENSOR).build();
        rule.getConditions().add(RuleCondition.builder().rule(rule).device(sensor).attribute("temperature")
                .operator(ConditionOperator.GT).expectedValue(JsonNodeFactory.instance.numberNode(30))
                .logicalOperator(LogicalOperator.AND).order(0).build());
        lenient().when(deviceRepository.findById(any())).thenAnswer(i -> Optional.of(
                List.of(sensor, fan, lamp).stream().filter(d -> d.getId().equals(i.getArgument(0))).findFirst().orElseThrow()));
        lenient().when(ruleRepository.findAllByHomeIdAndEnabledTrueAndTriggerTypeIn(eq(home.getId()), anyList())).thenReturn(List.of(rule));
        lenient().when(ruleRepository.findByIdAndHomeId(rule.getId(), home.getId())).thenReturn(Optional.of(rule));
        lenient().when(commandService.sendCommand(any(), any(), anyMap(), any()))
                .thenReturn(CompletableFuture.completedFuture(CommandResult.builder().success(true).status("ACKNOWLEDGED").build()));
        lenient().when(executionRepository.save(any())).thenAnswer(i -> {
            AutomationExecution execution = i.getArgument(0);
            if (execution.getId() == null) execution.setId(UUID.randomUUID());
            saved.put(execution.getId(), execution);
            return execution;
        });
        lenient().when(executionRepository.findById(any())).thenAnswer(i -> Optional.ofNullable(saved.get(i.<UUID>getArgument(0))));
    }

    @Test
    void groupStepCommandsEveryMemberThatCanDoIt() {
        DeviceGroup group = DeviceGroup.builder().id(UUID.randomUUID()).home(home).name("Tầng 1")
                .devices(new ArrayList<>(List.of(fan, lamp, sensor))).build();
        action(RuleAction.builder().group(group).action("TURN_ON").parameters(Map.of()));

        var execution = engine.process(home.getId(), reading(sensor, 35)).getExecutions().getFirst();

        assertThat(execution.getStatus()).isEqualTo("SUCCESS");
        assertThat(execution.getResultDetail()).hasSize(2); // the sensor cannot TURN_ON and is left out
        verify(commandService).sendCommand(eq(fan.getId()), eq("TURN_ON"), anyMap(), eq(StateChangeSource.AUTOMATION));
        verify(commandService).sendCommand(eq(lamp.getId()), eq("TURN_ON"), anyMap(), eq(StateChangeSource.AUTOMATION));
        verify(commandService, never()).sendCommand(eq(sensor.getId()), any(), anyMap(), any());
    }

    @Test
    void groupConditionHoldsWhenAnyMemberMatchesAndAnyMemberTriggers() {
        Device other = device("Temp 2", Map.of("sensor", List.of("GET_STATE")));
        DeviceGroup sensors = DeviceGroup.builder().id(UUID.randomUUID()).home(home).name("Cảm biến")
                .devices(new ArrayList<>(List.of(sensor, other))).build();
        rule.getConditions().getFirst().setDevice(null);
        rule.getConditions().getFirst().setGroup(sensors);
        action(RuleAction.builder().device(fan).action("TURN_ON").parameters(Map.of()));
        doReturn(Optional.of(other)).when(deviceRepository).findById(other.getId());

        assertThat(engine.process(home.getId(), reading(other, 25)).getMatchedRules()).isZero();
        assertThat(engine.process(home.getId(), reading(other, 35)).getMatchedRules()).isEqualTo(1);
        assertThat(engine.process(home.getId(), reading(lamp, 99)).getMatchedRules()).isZero(); // not in the group
    }

    @Test
    void delayStepRunsTheRestLaterAndTheRunIsPendingUntilThen() {
        action(RuleAction.builder().device(fan).action("TURN_ON").parameters(Map.of()));
        action(RuleAction.builder().action("DELAY").parameters(Map.of("seconds", 10)));
        action(RuleAction.builder().device(fan).action("TURN_OFF").parameters(Map.of()));

        var execution = engine.process(home.getId(), reading(sensor, 35)).getExecutions().getFirst();

        assertThat(execution.getStatus()).isEqualTo("PENDING");
        verifyNoInteractions(commandService);
        background.runAll();
        assertThat(sleeps).containsExactly(10_000L);
        var order = inOrder(commandService);
        order.verify(commandService).sendCommand(eq(fan.getId()), eq("TURN_ON"), anyMap(), any());
        order.verify(commandService).sendCommand(eq(fan.getId()), eq("TURN_OFF"), anyMap(), any());
        AutomationExecution done = saved.get(execution.getId());
        assertThat(done.getStatus()).isEqualTo(ExecutionStatus.SUCCESS); // the wait row does not count as a device result
        assertThat(done.getResultDetail().get(1).get("status").asText()).isEqualTo("WAITED");
    }

    @Test
    void autoOffSendsTheOppositeCommandAfterItsTimeUnlessChangedByHand() {
        action(RuleAction.builder().device(lamp).action("TURN_ON").parameters(Map.of("autoOffSeconds", 120)));

        engine.process(home.getId(), reading(sensor, 35));

        verify(commandService).sendCommand(lamp.getId(), "TURN_ON", Map.of(), StateChangeSource.AUTOMATION);
        background.runAll();
        assertThat(sleeps).containsExactly(120_000L);
        verify(commandService).sendCommand(lamp.getId(), "TURN_OFF", Map.of(), StateChangeSource.AUTOMATION);

        // Second run: the user touched the lamp meanwhile, so it is left alone.
        engine.process(home.getId(), reading(sensor, 20));
        engine.process(home.getId(), reading(sensor, 36));
        when(manualOverrideService.getOverridesUntil(List.of(lamp.getId())))
                .thenReturn(Map.of(lamp.getId(), java.time.OffsetDateTime.now().plusMinutes(5)));
        background.runAll();
        verify(commandService, times(1)).sendCommand(lamp.getId(), "TURN_OFF", Map.of(), StateChangeSource.AUTOMATION);
    }

    @Test
    void holdRunsOnlyWhenTheConditionStaysTrue() {
        // Real cooldown: starting a hold claims it, and a cancelled hold must hand it back.
        ReflectionTestUtils.setField(engine, "triggerGate", new com.hesta.backend.service.automation.RuleTriggerGate(60));
        rule.setHoldSeconds(60);
        action(RuleAction.builder().device(fan).action("TURN_ON").parameters(Map.of()));

        engine.process(home.getId(), reading(sensor, 35));
        engine.process(home.getId(), reading(sensor, 25)); // dropped below before 60 s passed
        background.runAll();
        verifyNoInteractions(commandService);

        engine.process(home.getId(), reading(sensor, 35));
        engine.process(home.getId(), reading(lamp, 0)); // unrelated device: must not cancel the hold
        background.runAll();
        assertThat(sleeps).contains(60_000L);
        verify(commandService).sendCommand(eq(fan.getId()), eq("TURN_ON"), anyMap(), eq(StateChangeSource.AUTOMATION));
    }

    private void action(RuleAction.RuleActionBuilder builder) {
        rule.getActions().add(builder.rule(rule).order(rule.getActions().size()).build());
    }

    private Device device(String name, Map<String, List<String>> capabilities) {
        return Device.builder().id(UUID.randomUUID()).room(Room.builder().home(home).build()).name(name)
                .deviceType("GENERIC").capabilities(new HashMap<>(capabilities)).build();
    }

    private static AutomationEventRequest reading(Device source, int temperature) {
        return AutomationEventRequest.builder().sourceDeviceId(source.getId()).eventType("SENSOR")
                .data(new HashMap<>(Map.of("temperature", temperature))).stateReport(true).build();
    }

    /** Queues tasks and runs them (and the tasks they queue) only when told to. */
    static final class ManualExecutor extends AbstractExecutorService {
        private final Deque<Runnable> queue = new ArrayDeque<>();

        void runAll() {
            while (!queue.isEmpty()) queue.poll().run();
        }

        @Override public void execute(Runnable command) { queue.add(command); }
        @Override public void shutdown() { }
        @Override public List<Runnable> shutdownNow() { return List.of(); }
        @Override public boolean isShutdown() { return false; }
        @Override public boolean isTerminated() { return false; }
        @Override public boolean awaitTermination(long timeout, TimeUnit unit) { return true; }
    }
}
