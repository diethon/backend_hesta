package com.hesta.backend.dto.response;

import lombok.*;
import lombok.experimental.FieldDefaults;

import java.util.List;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@FieldDefaults(level = AccessLevel.PRIVATE)
public class AdminUserStatsResponse {

    long totalUsers;
    long activeUsers;
    long lockedUsers;
    long disabledUsers;

    long adminUsers;
    long standardUsers;

    long localUsers;
    long googleUsers;

    List<RegistrationTrendPoint> registrationTrend;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @FieldDefaults(level = AccessLevel.PRIVATE)
    public static class RegistrationTrendPoint {
        String label;
        long count;
    }
}
