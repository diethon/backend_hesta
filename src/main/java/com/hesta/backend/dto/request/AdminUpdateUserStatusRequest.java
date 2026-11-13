package com.hesta.backend.dto.request;

import com.hesta.backend.enums.AccountStatus;
import jakarta.validation.constraints.NotNull;
import lombok.*;
import lombok.experimental.FieldDefaults;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AdminUpdateUserStatusRequest {

    @NotNull(message = "INVALID_REQUEST")
    AccountStatus status;
}
