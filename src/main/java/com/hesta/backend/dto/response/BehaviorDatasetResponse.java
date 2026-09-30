package com.hesta.backend.dto.response;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDate;

@Data
@Builder
public class BehaviorDatasetResponse {
    String datasetKey;
    LocalDate startDate;
    int days;
    long seed;
    int generatedEvents;
}
