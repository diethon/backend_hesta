package com.hesta.backend.maintenance.assistant;

import com.hesta.backend.ai.AiProperties;
import com.hesta.backend.entity.MaintenanceAssistantSetting;
import com.hesta.backend.repository.MaintenanceAssistantSettingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MaintenanceAssistantRolloutServiceTest {

    @Mock private MaintenanceAssistantSettingRepository settingRepository;

    private AiProperties aiProperties;
    private MaintenanceAssistantRolloutService service;

    @BeforeEach
    void setUp() {
        aiProperties = new AiProperties();
        aiProperties.setEnabled(true);
        when(settingRepository.findById(MaintenanceAssistantRolloutService.KEY_AI_KILL_SWITCH))
                .thenReturn(Optional.of(MaintenanceAssistantSetting.builder()
                        .settingKey(MaintenanceAssistantRolloutService.KEY_AI_KILL_SWITCH)
                        .settingValue("false")
                        .build()));
        service = new MaintenanceAssistantRolloutService(aiProperties, settingRepository);
    }

    @Test
    void aiTextAndImageAreEnabledByDefaultForAllHomes() {
        UUID anyHome = UUID.randomUUID();
        MaintenanceAssistantRolloutService.HomeCapabilities caps = service.resolveForHome(anyHome);
        assertThat(caps.aiTextEnabled()).isTrue();
        assertThat(caps.aiImageEnabled()).isTrue();
    }

    @Test
    void killSwitchAndAiDisabledImmediatelyShutOffAllLlm() {
        UUID homeId = UUID.randomUUID();
        assertThat(service.isAiTextEnabled(homeId)).isTrue();
        assertThat(service.isAiImageEnabled(homeId)).isTrue();

        when(settingRepository.findById(MaintenanceAssistantRolloutService.KEY_AI_KILL_SWITCH))
                .thenReturn(Optional.of(MaintenanceAssistantSetting.builder()
                        .settingKey(MaintenanceAssistantRolloutService.KEY_AI_KILL_SWITCH)
                        .settingValue("true")
                        .build()));
        assertThat(service.isAiTextEnabled(homeId)).isFalse();
        assertThat(service.isAiImageEnabled(homeId)).isFalse();

        when(settingRepository.findById(MaintenanceAssistantRolloutService.KEY_AI_KILL_SWITCH))
                .thenReturn(Optional.empty());
        aiProperties.setEnabled(false);
        assertThat(service.isAiTextEnabled(homeId)).isFalse();
        assertThat(service.isAiImageEnabled(homeId)).isFalse();
    }
}
