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
public class QrInfoResponse {
    private String payload;
    private String token;
    private OffsetDateTime expiresAt;
    @Builder.Default
    private boolean singleUse = true;
}
