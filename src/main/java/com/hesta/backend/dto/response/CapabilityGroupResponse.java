package com.hesta.backend.dto.response;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CapabilityGroupResponse {
    private String code;
    private String label;
    private String icon;
    private List<String> actions;
    private List<UUID> deviceIds;
    private int deviceCount;
}
