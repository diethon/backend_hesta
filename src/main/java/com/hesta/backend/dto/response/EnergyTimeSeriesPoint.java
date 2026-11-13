package com.hesta.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class EnergyTimeSeriesPoint {
    private OffsetDateTime timestamp;
    private String label; // "14:00", "Thứ Hai", "12/10"
    private double kwh;
    private long costVnd;
}
