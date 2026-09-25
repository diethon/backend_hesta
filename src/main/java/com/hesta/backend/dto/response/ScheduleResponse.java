package com.hesta.backend.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class ScheduleResponse {
    private UUID id;
    private LocalTime scheduledTime;
    private List<Short> repeatDays;
    private boolean active;
    private OffsetDateTime nextRunAt;
}
