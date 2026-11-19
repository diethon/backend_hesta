package com.hesta.backend.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalTime;
import java.util.List;
import java.util.UUID;

@Data
public class ScheduleRequest {
    /** Only in a rule's schedule list: an existing time to keep and update; null creates a new one. */
    private UUID id;

    @NotNull(message = "SCENE_SCHEDULE_INVALID")
    private LocalTime scheduledTime;

    @NotNull(message = "SCENE_SCHEDULE_INVALID")
    private List<Short> repeatDays;

    @NotNull(message = "SCENE_SCHEDULE_INVALID")
    private Boolean active;
}
