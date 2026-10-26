package com.hesta.backend.dto.request;

import com.hesta.backend.enums.PlatformRole;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AdminUpdateUserRequest {

    @NotBlank(message = "FULL_NAME_REQUIRED")
    @Size(max = 150, message = "FULL_NAME_INVALID")
    String fullName;

    @Size(max = 20, message = "INVALID_REQUEST")
    String phoneNumber;

    @NotNull(message = "INVALID_REQUEST")
    PlatformRole platformRole;
}
