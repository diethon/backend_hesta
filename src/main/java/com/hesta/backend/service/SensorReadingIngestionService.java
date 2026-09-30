package com.hesta.backend.service;

import com.hesta.backend.dto.command.SensorReadingInput;
import com.hesta.backend.dto.response.SensorReadingAcceptedResponse;
import java.util.UUID;

public interface SensorReadingIngestionService {
    SensorReadingAcceptedResponse ingest(UUID currentUserId, SensorReadingInput input);
}
