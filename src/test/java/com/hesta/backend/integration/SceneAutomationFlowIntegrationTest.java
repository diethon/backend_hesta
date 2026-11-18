package com.hesta.backend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.hesta.backend.config.mqtt.MqttGateway;
import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.request.AutomationEventRequest;
import com.hesta.backend.dto.response.MlRecommendationProposal;
import com.hesta.backend.entity.*;
import com.hesta.backend.enums.*;
import com.hesta.backend.repository.*;
import com.hesta.backend.service.AutomationEngine;
import com.hesta.backend.service.DeviceCommandService;
import com.hesta.backend.service.MlServiceClient;
import com.hesta.backend.service.RecommendationService;
import com.hesta.backend.service.SceneExecutionService;
import com.hesta.backend.service.impl.MqttDeviceCommandServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

/**
 * Scene / automation / recommendation through real transactions and the real MQTT command service; only the
 * broker is faked (it ACKs after a delay). The DB pool is deliberately tiny: a run that held a transaction while
 * waiting for ACKs would exhaust it and fail with a connection timeout.
 */
@SpringBootTest(properties = {
        "mqtt.command.timeout-ms=5000",
        "app.realtime.websocket.heartbeat=10s",
        "spring.datasource.hikari.maximum-pool-size=4",
        "spring.datasource.hikari.connection-timeout=2000",
        "hesta.scheduler.enabled=false",
        "hesta.recommendation.scheduler.enabled=false"
})
@ActiveProfiles("test")
class SceneAutomationFlowIntegrationTest {
    private static final long ACK_DELAY_MS = 700;

    @MockitoBean MqttGateway mqttGateway;
    @MockitoBean MlServiceClient mlServiceClient;

    @Autowired SceneExecutionService sceneExecutionService;
    @Autowired AutomationEngine automationEngine;
    @Autowired RecommendationService recommendationService;
    @Autowired DeviceCommandService deviceCommandService;
    @Autowired UserRepository users;
    @Autowired HomeRepository homes;
    @Autowired HomeMemberRepository members;
    @Autowired RoomRepository rooms;
    @Autowired DeviceRepository devices;
    @Autowired SceneRepository scenes;
    @Autowired AutomationRuleRepository rules;
    @Autowired SceneExecutionRepository sceneExecutions;
    @Autowired AutomationExecutionRepository automationExecutions;
    @Autowired AutomationRecommendationRepository recommendations;

    private final ObjectMapper mapper = new ObjectMapper();
    private User owner;
    private Home home;
    private Room room;

    @BeforeEach
    void setUp() {
        // The fake broker: every published command is ACKed by "the device" after ACK_DELAY_MS.
        doAnswer(invocation -> {
            String commandId = mapper.readTree((String) invocation.getArgument(2)).get("commandId").asText();
            CompletableFuture.delayedExecutor(ACK_DELAY_MS, TimeUnit.MILLISECONDS).execute(() -> {
                var pending = MqttDeviceCommandServiceImpl.pendingCommands.get(commandId);
                if (pending != null) pending.complete(CommandResult.builder().commandId(commandId)
                        .success(true).status("SUCCESS").build());
            });
            return null;
        }).when(mqttGateway).sendToMqtt(anyString(), anyInt(), anyString());

        owner = new User();
        owner.setEmail("owner" + UUID.randomUUID() + "@test.com");
        owner.setFullName("Owner");
        owner.setPasswordHash("hash");
        owner = users.save(owner);
        home = new Home();
        home.setName("Flow test home");
        home.setCreatedBy(owner);
        home = homes.save(home);
        members.save(HomeMember.builder().home(home).user(owner).role(HomeRole.OWNER).build());
        room = new Room();
        room.setName("Living room");
        room.setHome(home);
        room = rooms.save(room);
    }

    @Test
    void userRunsSceneAndEveryDeviceIsCommanded() {
        Device light = device("Light");
        Device fan = device("Fan");
        Scene scene = scene("Evening", light, fan);

        var result = sceneExecutionService.execute(owner.getId(), home.getId(), scene.getId());

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        assertThat(result.getResultDetail()).hasSize(2);
        assertThat(sceneExecutions.findById(result.getId())).isPresent();
        assertThat(devices.findById(light.getId()).orElseThrow().getCurrentState()).containsEntry("power", "ON");
    }

    @Test
    void sensorEventRunsRuleWithDeviceAndSceneActions() {
        Device sensor = device("Thermometer");
        Device fan = device("Fan");
        Device light = device("Light");
        Scene scene = scene("Cool down", light);
        AutomationRule rule = AutomationRule.builder().home(home).name("Hot room").enabled(true)
                .triggerType(TriggerType.SENSOR).build();
        rule.getConditions().add(RuleCondition.builder().rule(rule).device(sensor).attribute("temperature")
                .operator(ConditionOperator.GT).expectedValue(JsonNodeFactory.instance.numberNode(30)).order(0).build());
        rule.getActions().add(RuleAction.builder().rule(rule).device(fan).action("TURN_ON").order(0).build());
        rule.getActions().add(RuleAction.builder().rule(rule).scene(scene).action("EXECUTE_SCENE").order(1).build());
        rules.save(rule);

        var response = automationEngine.process(home.getId(), AutomationEventRequest.builder()
                .sourceDeviceId(sensor.getId()).eventType("SENSOR_READING").data(Map.of("temperature", 35)).build());

        assertThat(response.getMatchedRules()).isEqualTo(1);
        var execution = response.getExecutions().getFirst();
        assertThat(execution.getStatus()).isEqualTo("SUCCESS");
        assertThat(execution.getResultDetail().get(1).get("status").asText()).isEqualTo("SUCCESS");
        assertThat(automationExecutions.findById(execution.getId())).isPresent();
    }

    @Test
    void manyScheduledScenesAtOnceDoNotExhaustTheConnectionPool() throws Exception {
        // 8 scenes x 2 commands, each ACK 700 ms, on a 4-connection pool. Holding a transaction per run while
        // waiting for ACKs would need 8+ connections at once and time out after 2 s.
        List<Scene> batch = new ArrayList<>();
        for (int i = 0; i < 8; i++) batch.add(scene("Batch " + i, device("A" + i), device("B" + i)));
        ExecutorService pool = Executors.newFixedThreadPool(batch.size());
        try {
            List<Future<String>> runs = new ArrayList<>();
            for (Scene scene : batch) {
                runs.add(pool.submit(() -> sceneExecutionService.executeScheduled(home.getId(), scene.getId()).getStatus()));
            }
            for (Future<String> run : runs) assertThat(run.get(30, TimeUnit.SECONDS)).isEqualTo("SUCCESS");
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void twoPeoplePressingTheSameDeviceSendsOnlyOneCommand() throws Exception {
        Device light = device("Shared light");
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int round = 0; round < 20; round++) {
                CountDownLatch start = new CountDownLatch(1);
                String action = round % 2 == 0 ? "TURN_ON" : "TURN_OFF";
                Callable<CommandResult> press = () -> {
                    start.await();
                    return deviceCommandService.sendCommand(light.getId(), action, Map.of(), StateChangeSource.MANUAL).join();
                };
                Future<CommandResult> first = pool.submit(press);
                Future<CommandResult> second = pool.submit(press);
                start.countDown();
                List<CommandResult> results = List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
                assertThat(results).as("round %d: exactly one press wins", round)
                        .filteredOn(CommandResult::isSuccess).hasSize(1);
                assertThat(results).filteredOn(result -> !result.isSuccess())
                        .extracting(CommandResult::getErrorCode).containsOnly("COMMAND_IN_PROGRESS_DUPLICATE_REJECTED");
                Thread.sleep(450); // past the 400 ms anti-spam window before the next round
            }
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void recommendationsAreGeneratedAndStored() {
        Device light = device("Porch light");
        when(mlServiceClient.generateRecommendations(eq(home.getId()), any(), any(), anyDouble())).thenReturn(List.of(
                MlRecommendationProposal.builder().deviceId(light.getId()).deviceName(light.getName()).action("TURN_ON")
                        .time("18:30:00").confidence(0.93).eligible(true).engineType("ML").modelVersion("test").build()));

        var created = recommendationService.generateForSystem(home.getId(), OffsetDateTime.now().minusDays(14), OffsetDateTime.now());

        assertThat(created).hasSize(1);
        assertThat(recommendations.findAllByHomeIdOrderByCreatedAtDesc(home.getId())).hasSize(1);
    }

    private Device device(String name) {
        Device device = new Device();
        device.setName(name);
        device.setDeviceType("LIGHT");
        device.setStatus(DeviceStatus.ONLINE);
        device.setRoom(room);
        device.setCurrentState(new HashMap<>(Map.of("power", "OFF")));
        return devices.save(device);
    }

    private Scene scene(String name, Device... targets) {
        Scene scene = Scene.builder().home(home).name(name).enabled(true).build();
        for (int i = 0; i < targets.length; i++) {
            scene.getActions().add(SceneAction.builder().scene(scene).targetDevice(targets[i]).action("TURN_ON").order(i).build());
        }
        return scenes.save(scene);
    }
}
