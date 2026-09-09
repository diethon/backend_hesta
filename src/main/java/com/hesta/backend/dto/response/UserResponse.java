package com.hesta.backend.dto.response;

import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.enums.AuthProvider;
import com.hesta.backend.enums.PlatformRole;
import lombok.*;
import lombok.experimental.FieldDefaults;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class UserResponse {

    UUID id;
    String fullName;
    String email;
    String phoneNumber;
    String avatarUrl;
    AuthProvider provider;
    PlatformRole platformRole;
    AccountStatus status;
    OffsetDateTime createdAt;
    OffsetDateTime lastActiveAt;
}
