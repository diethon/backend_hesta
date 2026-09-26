package com.hesta.backend.dto.request;

import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.time.LocalTime;
import java.util.List;

@Data
public class ScheduleRequest {
    @NotNull(message = "SCENE_SCHEDULE_INVALID")
    private LocalTime scheduledTime;

    @NotNull(message = "SCENE_SCHEDULE_INVALID")
    private List<Short> repeatDays;

    @NotNull(message = "SCENE_SCHEDULE_INVALID")
    private Boolean active;
}
