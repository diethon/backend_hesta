package com.hesta.backend.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.config.mqtt.MqttGateway;
import com.hesta.backend.dto.command.CommandResult;
import com.hesta.backend.dto.request.CreateAutomationRuleRequest;
import com.hesta.backend.dto.request.RuleActionRequest;
import com.hesta.backend.dto.request.RuleConditionRequest;
import com.hesta.backend.entity.*;
import com.hesta.backend.enums.*;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.repository.*;
import com.hesta.backend.service.AutomationRuleService;
import com.hesta.backend.service.DeviceCommandService;
import com.hesta.backend.service.DeviceGroupService;
import com.hesta.backend.service.MlServiceClient;
import com.hesta.backend.service.impl.MqttDeviceCommandServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

/** Group targets/conditions, wait steps, auto-off and hold time saved through the real service and database. */
@SpringBootTest(properties = {
        "mqtt.command.timeout-ms=5000",
        "hesta.scheduler.enabled=false",
        "hesta.recommendation.scheduler.enabled=false"
})
@ActiveProfiles("test")
class AutomationGroupRuleIntegrationTest {
    @MockitoBean MqttGateway mqttGateway;
    @MockitoBean MlServiceClient mlServiceClient;

    @Autowired AutomationRuleService ruleService;
    @Autowired DeviceGroupService groupService;
    @Autowired DeviceCommandService deviceCommandService;
    @Autowired UserRepository users;
    @Autowired HomeRepository homes;
    @Autowired HomeMemberRepository members;
    @Autowired RoomRepository rooms;
    @Autowired DeviceRepository devices;
    @Autowired DeviceGroupRepository groups;
    @Autowired AutomationRuleRepository rules;
    @Autowired AutomationExecutionRepository executions;

    private final ObjectMapper mapper = new ObjectMapper();
    private User owner;
    private Home home;
    private Room room;

    @BeforeEach
    void setUp() {
        doAnswer(invocation -> {
            String commandId = mapper.readTree((String) invocation.getArgument(2)).get("commandId").asText();
            CompletableFuture.delayedExecutor(100, TimeUnit.MILLISECONDS).execute(() -> {
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
        home.setName("Group test home");
        home.setCreatedBy(owner);
        home = homes.save(home);
        members.save(HomeMember.builder().home(home).user(owner).role(HomeRole.OWNER).build());
        room = new Room();
        room.setName("Living room");
        room.setHome(home);
        room = rooms.save(room);
    }

    @Test
    void groupRuleWithWaitAutoOffAndHoldIsSavedAndReadBack() {
        Device sensor = device("Temp", "TEMPERATURE_SENSOR");
        Device lamp = device("Lamp", "LIGHT");
        Device fan = device("Fan", "LIGHT");
        DeviceGroup lights = group("Đèn", lamp, fan);
        DeviceGroup sensors = group("Cảm biến", sensor);

        var saved = ruleService.create(owner.getId(), home.getId(), CreateAutomationRuleRequest.builder()
                .name("Nóng thì bật đèn").triggerType("SENSOR").enabled(true).holdSeconds(30)
                .conditions(List.of(condition(sensors.getId())))
                .actions(List.of(
                        RuleActionRequest.builder().groupId(lights.getId()).action("TURN_ON")
                                .parameters(new HashMap<>(Map.of("autoOffSeconds", 60))).order(0).build(),
                        RuleActionRequest.builder().action("DELAY").parameters(new HashMap<>(Map.of("seconds", 5))).order(1).build(),
                        RuleActionRequest.builder().deviceId(fan.getId()).action("TURN_OFF").order(2).build()))
                .build());

        var read = ruleService.get(owner.getId(), home.getId(), saved.getId());
        assertThat(read.getHoldSeconds()).isEqualTo(30);
        assertThat(read.getConditions().getFirst().getGroupId()).isEqualTo(sensors.getId());
        assertThat(read.getConditions().getFirst().getGroupName()).isEqualTo("Cảm biến");
        assertThat(read.getActions().get(0).getGroupName()).isEqualTo("Đèn");
        assertThat(read.getActions().get(0).getParameters()).containsEntry("autoOffSeconds", 60);
        assertThat(read.getActions().get(1).getAction()).isEqualTo("DELAY");
        assertThat(read.getActions().get(1).getParameters()).containsEntry("seconds", 5);
    }

    @Test
    void invalidTimingAndForeignGroupsAreRejected() {
        Device sensor = device("Temp", "TEMPERATURE_SENSOR");
        Device lamp = device("Lamp", "LIGHT");
        DeviceGroup lights = group("Đèn", lamp);

        Home other = new Home();
        other.setName("Other");
        other.setCreatedBy(owner);
        other = homes.save(other);
        DeviceGroup foreign = groups.save(DeviceGroup.builder().home(other).name("Khác").devices(new ArrayList<>(List.of(lamp))).build());

        RuleActionRequest turnOn = RuleActionRequest.builder().deviceId(lamp.getId()).action("TURN_ON").order(0).build();
        assertRejected(List.of(RuleActionRequest.builder().action("DELAY").parameters(new HashMap<>(Map.of("seconds", 5))).order(0).build()), 0, sensor);
        assertRejected(List.of(turnOn, RuleActionRequest.builder().action("DELAY").parameters(new HashMap<>(Map.of("seconds", 0))).order(1).build()), 0, sensor);
        assertRejected(List.of(turnOn, RuleActionRequest.builder().action("DELAY").parameters(new HashMap<>(Map.of("seconds", 4000))).order(1).build()), 0, sensor);
        assertRejected(List.of(RuleActionRequest.builder().deviceId(lamp.getId()).action("TURN_OFF")
                .parameters(new HashMap<>(Map.of("autoOffSeconds", 60))).order(0).build()), 0, sensor); // nothing to undo
        assertRejected(List.of(turnOn), 3601, sensor);
        assertRejected(List.of(RuleActionRequest.builder().groupId(foreign.getId()).action("TURN_ON").order(0).build()), 0, sensor);
        assertRejected(List.of(RuleActionRequest.builder().groupId(lights.getId()).deviceId(lamp.getId()).action("TURN_ON").order(0).build()), 0, sensor);
    }

    @Test
    void deletingAGroupDetachesItAndDisablesRulesThatWatchedIt() {
        Device sensor = device("Temp", "TEMPERATURE_SENSOR");
        Device lamp = device("Lamp", "LIGHT");
        DeviceGroup sensors = group("Cảm biến", sensor);
        DeviceGroup lights = group("Đèn", lamp);
        var watching = ruleService.create(owner.getId(), home.getId(), CreateAutomationRuleRequest.builder()
                .name("Theo nhóm").triggerType("SENSOR").enabled(true).conditions(List.of(condition(sensors.getId())))
                .actions(List.of(RuleActionRequest.builder().deviceId(lamp.getId()).action("TURN_ON").order(0).build())).build());
        var commanding = ruleService.create(owner.getId(), home.getId(), CreateAutomationRuleRequest.builder()
                .name("Bật nhóm").triggerType("SENSOR").enabled(true).conditions(List.of(deviceCondition(sensor.getId())))
                .actions(List.of(
                        RuleActionRequest.builder().groupId(lights.getId()).action("TURN_ON").order(0).build(),
                        RuleActionRequest.builder().deviceId(lamp.getId()).action("TURN_OFF").order(1).build())).build());

        groupService.deleteGroup(owner.getId(), sensors.getId());
        groupService.deleteGroup(owner.getId(), lights.getId());

        var first = ruleService.get(owner.getId(), home.getId(), watching.getId());
        assertThat(first.isEnabled()).isFalse();
        assertThat(first.getConditions()).isEmpty();
        var second = ruleService.get(owner.getId(), home.getId(), commanding.getId());
        assertThat(second.isEnabled()).isTrue(); // still has a real step
        assertThat(second.getActions()).singleElement().satisfies(a -> {
            assertThat(a.getAction()).isEqualTo("TURN_OFF");
            assertThat(a.getOrder()).isZero();
        });
    }

    @Test
    void aDeviceTurnedOnByCommandTriggersRulesWatchingIt() throws Exception {
        Device lamp = device("Lamp", "LIGHT");
        Device fan = device("Fan", "LIGHT");
        var rule = ruleService.create(owner.getId(), home.getId(), CreateAutomationRuleRequest.builder()
                .name("Đèn bật thì bật quạt").triggerType("EVENT").enabled(true)
                .conditions(List.of(RuleConditionRequest.builder().deviceId(lamp.getId()).attribute("power")
                        .operator("EQ").expectedValue("ON").order(0).build()))
                .actions(List.of(RuleActionRequest.builder().deviceId(fan.getId()).action("TURN_ON").order(0).build())).build());

        deviceCommandService.sendCommand(lamp.getId(), "TURN_ON", Map.of(), StateChangeSource.MANUAL).get(5, TimeUnit.SECONDS);

        long deadline = System.currentTimeMillis() + 8000;
        while (executions.findTop100ByRuleIdOrderByMatchedAtDesc(rule.getId()).isEmpty() && System.currentTimeMillis() < deadline) {
            Thread.sleep(100);
        }
        assertThat(executions.findTop100ByRuleIdOrderByMatchedAtDesc(rule.getId())).isNotEmpty();
        assertThat(devices.findById(fan.getId()).orElseThrow().getCurrentState()).containsEntry("power", "ON");
    }

    @Test
    void listShowsTheTimesOfScheduleRules() {
        Device lamp = device("Lamp", "LIGHT");
        ruleService.create(owner.getId(), home.getId(), CreateAutomationRuleRequest.builder()
                .name("Bật đèn 18:00").triggerType("SCHEDULE").enabled(true)
                .actions(List.of(RuleActionRequest.builder().deviceId(lamp.getId()).action("TURN_ON").order(0).build()))
                .schedules(List.of(schedule(java.time.LocalTime.of(18, 0), List.of((short) 1, (short) 5))))
                .build());

        var listed = ruleService.getAll(owner.getId(), home.getId());
        assertThat(listed).singleElement().satisfies(r -> {
            assertThat(r.getSchedules()).singleElement().satisfies(t -> {
                assertThat(t.getScheduledTime()).isEqualTo(java.time.LocalTime.of(18, 0));
                assertThat(t.getRepeatDays()).containsExactly((short) 1, (short) 5);
            });
        });
    }

    private static com.hesta.backend.dto.request.ScheduleRequest schedule(java.time.LocalTime time, List<Short> days) {
        var request = new com.hesta.backend.dto.request.ScheduleRequest();
        request.setScheduledTime(time);
        request.setRepeatDays(days);
        request.setActive(true);
        return request;
    }

    private void assertRejected(List<RuleActionRequest> actions, int holdSeconds, Device sensor) {
        assertThatThrownBy(() -> ruleService.create(owner.getId(), home.getId(), CreateAutomationRuleRequest.builder()
                .name("Sai " + UUID.randomUUID()).triggerType("SENSOR").enabled(true).holdSeconds(holdSeconds)
                .conditions(List.of(deviceCondition(sensor.getId()))).actions(actions).build()))
                .isInstanceOf(AppException.class);
    }

    private static RuleConditionRequest condition(UUID groupId) {
        return RuleConditionRequest.builder().groupId(groupId).attribute("temperature").operator("GT").expectedValue(30).order(0).build();
    }

    private static RuleConditionRequest deviceCondition(UUID deviceId) {
        return RuleConditionRequest.builder().deviceId(deviceId).attribute("temperature").operator("GT").expectedValue(30).order(0).build();
    }

    private DeviceGroup group(String name, Device... members) {
        return groups.save(DeviceGroup.builder().home(home).name(name).devices(new ArrayList<>(List.of(members))).build());
    }

    private Device device(String name, String type) {
        Device device = new Device();
        device.setName(name);
        device.setDeviceType(type);
        device.setStatus(DeviceStatus.ONLINE);
        device.setRoom(room);
        device.setCurrentState(new HashMap<>(Map.of("power", "OFF")));
        return devices.save(device);
    }
}
