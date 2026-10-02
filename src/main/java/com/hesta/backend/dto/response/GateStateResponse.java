package com.hesta.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GateStateResponse {
    private UUID deviceId;
    private String state;
    private Boolean limitOpen;
    private Boolean limitClose;
    private Map<String, Object> currentState;
}
