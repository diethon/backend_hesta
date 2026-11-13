package com.hesta.backend.service.execution;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.service.execution.DeviceActionValues.DeviceCommand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class DeviceActionValuesTest {
    private final ObjectMapper mapper = new ObjectMapper();

    @Test
    void sceneValueAndRuleParametersFollowTheSameRules() {
        assertThat(DeviceActionValues.isValidValue("SET_BRIGHTNESS", mapper.valueToTree(75))).isTrue();
        assertThat(DeviceActionValues.isValidValue("SET_BRIGHTNESS", mapper.valueToTree(150))).isFalse();
        assertThat(DeviceActionValues.isValidParameters("SET_BRIGHTNESS", Map.of("level", 40))).isTrue();
        assertThat(DeviceActionValues.isValidValue("TURN_ON", mapper.valueToTree(true))).isFalse();
        assertThat(DeviceActionValues.isValidParameters("TURN_ON", Map.of("temperature", 24))).isTrue();
        assertThat(DeviceActionValues.isValidParameters("SET_TIMER", Map.of("hour", 30))).isFalse();
        assertThat(DeviceActionValues.isValidParameters("SET_COLOR", Map.of("r", 1, "g", 2, "b", 3))).isTrue();
    }

    @Test
    void sceneValueBecomesCommandParameters() {
        assertThat(DeviceActionValues.toParameters("SET_TEMPERATURE", mapper.valueToTree(24)))
                .containsEntry("temperature", 24).containsEntry("temp", 24);
        assertThat(DeviceActionValues.toParameters("SET_COLOR", mapper.valueToTree("#ff0000")))
                .containsEntry("r", 255).containsEntry("g", 0).containsEntry("hex", "#ff0000");
        assertThat(DeviceActionValues.toParameters("TURN_ON", null)).isEmpty();
    }

    @Test
    void setStateSplitsIntoSingleCommands() {
        assertThat(DeviceActionValues.expandSetState(Map.of("power", "OFF", "brightness", 50)))
                .extracting(DeviceCommand::action).containsExactly("TURN_OFF");
        assertThat(DeviceActionValues.expandSetState(Map.of("power", true, "temperature", 24, "mode", "cool")))
                .extracting(DeviceCommand::action).containsExactly("TURN_ON", "SET_TEMPERATURE", "SET_MODE");
        // Colour already carries power and brightness: one command instead of three.
        List<DeviceCommand> light = DeviceActionValues.expandSetState(Map.of("power", "ON", "brightness", 30, "hex", "#00ff00"));
        assertThat(light).extracting(DeviceCommand::action).containsExactly("SET_COLOR");
        assertThat(light.getFirst().parameters()).containsEntry("brightness", 30).containsEntry("g", 255);
    }

    @Test
    void habitActionResolvesToggleAndAliases() {
        assertThat(DeviceActionValues.habitAction("TOGGLE", Map.of(), Map.of("power", "ON"))).isEqualTo("TURN_OFF");
        assertThat(DeviceActionValues.habitAction("toggle", Map.of(), Map.of("power", false))).isEqualTo("TURN_ON");
        assertThat(DeviceActionValues.habitAction("TOGGLE", Map.of(), null)).isEqualTo("TURN_ON");
        assertThat(DeviceActionValues.habitAction("POWER_OFF", Map.of(), Map.of())).isEqualTo("TURN_OFF");
        assertThat(DeviceActionValues.habitAction("SET_POWER", Map.of("power", true), Map.of())).isEqualTo("TURN_ON");
        assertThat(DeviceActionValues.habitAction("SET_STATE", Map.of("power", "OFF"), Map.of())).isEqualTo("TURN_OFF");
        assertThat(DeviceActionValues.habitAction("GATE_OPEN", Map.of(), Map.of())).isEqualTo("OPEN");
        assertThat(DeviceActionValues.habitAction("SET_BRIGHTNESS", Map.of("brightness", 40), Map.of())).isEqualTo("SET_BRIGHTNESS");
    }
}
