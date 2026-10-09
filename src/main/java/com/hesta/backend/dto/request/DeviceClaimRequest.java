package com.hesta.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DeviceClaimRequest {

    @NotBlank(message = "Token không được để trống")
    private String token;

    @NotNull(message = "homeId không được để trống")
    private UUID homeId;

    @NotNull(message = "roomId không được để trống")
    private UUID roomId;

    private String name;

    private String nodeCode;
}
