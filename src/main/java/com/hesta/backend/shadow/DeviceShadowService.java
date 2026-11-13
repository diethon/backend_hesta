package com.hesta.backend.shadow;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public interface DeviceShadowService {

    DeviceShadowDto getShadow(UUID deviceId);

    DeviceShadowDto updateDesiredState(UUID deviceId, Map<String, Object> desiredState);

    DeviceShadowDto updateReportedState(UUID deviceId, Map<String, Object> reportedState, Long incomingVersion);

    DeviceShadowDto reconcile(UUID deviceId);

    List<DeviceShadowDto> getUnsyncedShadows();
}
