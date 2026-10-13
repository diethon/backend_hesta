package com.hesta.backend.controller;

import com.hesta.backend.dto.request.MockSensorReadingRequest;
import com.hesta.backend.dto.response.ApiResponse;
import com.hesta.backend.dto.response.SensorReadingAcceptedResponse;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.SensorReadingIngestionService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Profile;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

/** Explicit opt-in development adapter. Never enable this profile on production. */
@RestController
@Profile("mock-sensors & !prod & !production")
@ConditionalOnProperty(name = "app.mock-sensors.enabled", havingValue = "true")
@RequestMapping("/api/v1/dev/sensors/mock-reading")
@RequiredArgsConstructor
public class MockSensorController {
    private final SensorReadingIngestionService ingestionService;

    @PostMapping
    public ApiResponse<SensorReadingAcceptedResponse> accept(
            @AuthenticationPrincipal CustomUserDetails currentUser, @RequestBody MockSensorReadingRequest request) {
        return ApiResponse.<SensorReadingAcceptedResponse>builder()
                .result(ingestionService.ingest(currentUser.getId(), request.toInput())).build();
    }
}
