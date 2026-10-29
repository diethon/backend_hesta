package com.hesta.backend.service;

import com.hesta.backend.dto.response.TwinHomeSnapshotResponse;

import java.util.UUID;

public interface TwinSnapshotService {
    TwinHomeSnapshotResponse getSnapshot(UUID userId, UUID homeId);
}
