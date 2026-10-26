package com.hesta.backend.energy.demandresponse;

import com.hesta.backend.entity.Device;

import java.util.UUID;

public interface DemandResponseService {

    DemandResponseSettings getSettings(UUID homeId);

    DemandResponseSettings updateSettings(UUID homeId, DemandResponseSettings settings);

    DemandResponseEvaluationResult evaluateAndShed(UUID homeId, Double simulatedWatts);

    DevicePriorityCategory classifyDevicePriority(Device device);
}
