package com.hesta.backend.dto.request;

import com.hesta.backend.enums.PlatformRole;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AdminCreateUserRequest {

    @NotBlank(message = "FULL_NAME_REQUIRED")
    @Size(max = 150, message = "FULL_NAME_INVALID")
    String fullName;

    @NotBlank(message = "EMAIL_REQUIRED")
    @Email(message = "INVALID_EMAIL")
    @Size(max = 255, message = "INVALID_EMAIL")
    String email;

    @Size(max = 20, message = "INVALID_REQUEST")
    String phoneNumber;

    @NotBlank(message = "PASSWORD_REQUIRED")
    @Size(min = 8, message = "PASSWORD_TOO_SHORT")
    String password;

    PlatformRole platformRole;
}
