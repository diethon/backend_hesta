package com.hesta.backend.service;

import com.hesta.backend.dto.response.ManualCommandResponse;
import com.hesta.backend.dto.response.ManualOverrideResponse;

import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.List;

public interface ManualControlService {
    ManualCommandResponse command(UUID userId, UUID deviceId, String action);
    OffsetDateTime cancelAutomation(UUID userId, UUID deviceId);
    List<ManualOverrideResponse> history(UUID userId, UUID deviceId);
}
