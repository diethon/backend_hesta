package com.hesta.backend.maintenance.playbook;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

class PlaybookServiceTest {

    private final PlaybookService playbookService = new PlaybookService();

    @Test
    void loadsAllTwelvePlaybooksAndMapsEveryRealDeviceTypeFromCodeAndDb() {
        assertThat(playbookService.getAll()).hasSize(12);

        // Light group
        assertThat(playbookService.getForDeviceType("LIGHT").getId()).isEqualTo("LIGHT");
        assertThat(playbookService.getForDeviceType("LED").getId()).isEqualTo("LIGHT");
        assertThat(playbookService.getForDeviceType("LED_RGB").getId()).isEqualTo("LIGHT");

        // Smart plug group
        assertThat(playbookService.getForDeviceType("SMART_PLUG").getId()).isEqualTo("SMART_PLUG");
        assertThat(playbookService.getForDeviceType("SOCKET").getId()).isEqualTo("SMART_PLUG");

        // Environmental sensor group (MOTION_SENSOR & LUX_SENSOR stay here)
        for (String sensor : List.of("TEMP_HUMID_SENSOR", "MOTION_SENSOR", "LUX_SENSOR")) {
            assertThat(playbookService.getForDeviceType(sensor).getId()).isEqualTo("TEMP_HUMID_SENSOR");
        }

        // Dedicated SAFETY_SENSOR group
        for (String safetySensor : List.of("SMOKE_SENSOR", "FLAME_SENSOR", "GAS_SENSOR")) {
            PlaybookDefinition safetyDef = playbookService.getForDeviceType(safetySensor);
            assertThat(safetyDef.getId()).isEqualTo("SAFETY_SENSOR");
            assertThat(safetyDef.getOpeningWarning()).contains("không có cảnh báo khói/lửa");
            assertThat(safetyDef.getMinEscalationPriority()).isEqualTo("HIGH");
        }

        // Air conditioner group (including AC_REMOTE found in catalog DB)
        for (String ac : List.of("AIR_CONDITIONER", "AC", "AC_REMOTE")) {
            assertThat(playbookService.getForDeviceType(ac).getId()).isEqualTo("AIR_CONDITIONER");
        }

        // Dedicated motor / hardwired / IR playbooks
        assertThat(playbookService.getForDeviceType("FAN").getId()).isEqualTo("FAN");
        assertThat(playbookService.getForDeviceType("CURTAIN").getId()).isEqualTo("CURTAIN");
        assertThat(playbookService.getForDeviceType("WATER_PUMP").getId()).isEqualTo("WATER_PUMP");
        assertThat(playbookService.getForDeviceType("IR_REMOTE").getId()).isEqualTo("IR_REMOTE");
        for (String doorGate : List.of("DOOR", "GATE", "ROLLING_DOOR", "GATE_MOTOR")) {
            assertThat(playbookService.getForDeviceType(doorGate).getId()).isEqualTo("DOOR_GATE");
        }

        // Camera AI
        assertThat(playbookService.getForDeviceType("CAMERA_AI").getId()).isEqualTo("CAMERA_AI");

        // Remaining types mapped to GENERIC (TV stays GENERIC)
        for (String fallbackType : List.of("TV", "UNKNOWN_DEVICE")) {
            assertThat(playbookService.getForDeviceType(fallbackType).getId()).isEqualTo("GENERIC");
        }
    }

    @Test
    void safetySensorAndMotorPlaybooksObeyStrictSafetyConstraints() {
        PlaybookDefinition safetySensor = playbookService.getForDeviceType("SMOKE_SENSOR");
        for (PlaybookDefinition.Symptom s : safetySensor.getSymptoms()) {
            for (PlaybookDefinition.Step step : s.getSteps()) {
                String lower = (step.getTitle() + " " + step.getInstruction()).toLowerCase(Locale.ROOT);
                assertThat(lower).doesNotContain("tắt cảnh báo", "tắt còi", "rút phích", "ngắt cầu dao");
            }
        }

        PlaybookDefinition waterPump = playbookService.getForDeviceType("WATER_PUMP");
        assertThat(waterPump.getSafety().getAdvice().toLowerCase(Locale.ROOT)).contains("rò nước gần nguồn điện");
        assertThat(waterPump.getSymptoms().stream()
                .flatMap(s -> s.getSteps().stream())
                .map(st -> st.getInstruction().toLowerCase(Locale.ROOT)))
                .anyMatch(instr -> instr.contains("rò nước gần"));
    }

    @Test
    void motorAndGenericPlaybooksNeverInstructUnpluggingOrFlippingBreaker() {
        for (String id : List.of("DOOR_GATE", "CURTAIN", "WATER_PUMP", "FAN", "GENERIC")) {
            PlaybookDefinition def = playbookService.getForDeviceType(id.equals("DOOR_GATE") ? "DOOR" : id);
            assertThat(def.getSafety()).isNotNull();
            assertThat(def.getImageHints()).isNotEmpty();
            for (PlaybookDefinition.Symptom symptom : def.getSymptoms()) {
                assertThat(symptom.getSteps().size()).isLessThanOrEqualTo(PlaybookService.MAX_STEPS_PER_SYMPTOM);
                for (PlaybookDefinition.Step step : symptom.getSteps()) {
                    String lower = (step.getTitle() + " " + step.getInstruction()).toLowerCase(Locale.ROOT);
                    assertThat(lower).doesNotContain("rút phích", "ngắt cầu dao", "ngắt aptomat");
                }
            }
        }
    }

    @Test
    void allPlaybooksRespectMaxStepsAndSafetyKeywords() {
        for (PlaybookDefinition def : playbookService.getAll()) {
            assertThat(def.getSafety()).isNotNull();
            assertThat(playbookService.matchesSafetyKeyword(def, "Thiết bị có mùi khét và nóng bất thường")).isTrue();
            for (PlaybookDefinition.Symptom symptom : def.getSymptoms()) {
                assertThat(symptom.getSteps().size()).isLessThanOrEqualTo(PlaybookService.MAX_STEPS_PER_SYMPTOM);
            }
        }
    }
}
