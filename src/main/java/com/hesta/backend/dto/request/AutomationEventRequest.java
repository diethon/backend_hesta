package com.hesta.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AutomationEventRequest {
    UUID sourceDeviceId;

    @NotBlank(message = "AUTOMATION_EVENT_INVALID")
    String eventType;

    @NotNull(message = "AUTOMATION_EVENT_INVALID")
    @Builder.Default
    Map<String, Object> data = new HashMap<>();

    OffsetDateTime occurredAt;

    @Builder.Default
    boolean test = false;
}
