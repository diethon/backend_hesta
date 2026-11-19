package com.hesta.backend.service;

import com.hesta.backend.dto.request.TwinLayoutSaveRequest;
import com.hesta.backend.dto.response.TwinLayoutResponse;

import java.util.UUID;

public interface TwinLayoutService {
    TwinLayoutResponse getLayout(UUID userId, UUID homeId);

    TwinLayoutResponse saveLayout(UUID userId, UUID homeId, TwinLayoutSaveRequest request);
}
