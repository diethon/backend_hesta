package com.hesta.backend.maintenance.assistant;

import com.hesta.backend.ai.AiProperties;
import com.hesta.backend.entity.MaintenanceAssistantSetting;
import com.hesta.backend.repository.MaintenanceAssistantSettingRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * AI văn bản và AI ảnh bật mặc định cho mọi nhà khi hesta.ai.enabled=true,
 * chỉ chịu tác động của công tắc khẩn cấp (kill switch) đọc từ DB mỗi request.
 */
@Service
@RequiredArgsConstructor
public class MaintenanceAssistantRolloutService {

    public static final String KEY_AI_KILL_SWITCH = "ai_kill_switch";

    private final AiProperties aiProperties;
    private final MaintenanceAssistantSettingRepository settingRepository;

    public record HomeCapabilities(boolean aiTextEnabled, boolean aiImageEnabled) {
    }

    public record AssistantStatusDto(boolean aiConfigEnabled, boolean killSwitchActive) {
    }

    @Transactional(readOnly = true)
    public boolean isKillSwitchActive() {
        return settingRepository.findById(KEY_AI_KILL_SWITCH)
                .map(s -> "true".equalsIgnoreCase(s.getSettingValue()))
                .orElse(false);
    }

    @Transactional(readOnly = true)
    public AssistantStatusDto getStatus() {
        return new AssistantStatusDto(aiProperties.isEnabled(), isKillSwitchActive());
    }

    @Transactional(readOnly = true)
    public HomeCapabilities resolveForHome(UUID homeId) {
        boolean active = aiProperties.isEnabled() && !isKillSwitchActive();
        return new HomeCapabilities(active, active);
    }

    @Transactional(readOnly = true)
    public boolean isAiTextEnabled(UUID homeId) {
        return resolveForHome(homeId).aiTextEnabled();
    }

    @Transactional(readOnly = true)
    public boolean isAiImageEnabled(UUID homeId) {
        return resolveForHome(homeId).aiImageEnabled();
    }

    @Transactional
    public boolean setKillSwitch(boolean active) {
        MaintenanceAssistantSetting setting = settingRepository.findById(KEY_AI_KILL_SWITCH)
                .orElseGet(() -> MaintenanceAssistantSetting.builder().settingKey(KEY_AI_KILL_SWITCH).build());
        setting.setSettingValue(Boolean.toString(active));
        settingRepository.save(setting);
        return active;
    }
}
