package com.hesta.backend.dto.request;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class UpdateProfileRequest {
    @NotBlank(message = "Tên không được để trống")
    private String fullName;

    private String phoneNumber;
}
