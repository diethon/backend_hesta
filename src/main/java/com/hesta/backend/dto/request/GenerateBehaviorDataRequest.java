package com.hesta.backend.dto.request;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.*;

import java.time.LocalDate;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GenerateBehaviorDataRequest {
    @NotNull(message = "BEHAVIOR_DATASET_INVALID")
    LocalDate startDate;

    @Min(value = 2, message = "BEHAVIOR_DATASET_INVALID")
    @Max(value = 365, message = "BEHAVIOR_DATASET_INVALID")
    int days;

    long seed;
}
