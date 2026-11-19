package com.hesta.backend.falldetection.service;

import com.hesta.backend.falldetection.dto.FallIncidentEventDTO;
import com.hesta.backend.falldetection.dto.FallIncidentResponseDTO;

import java.util.List;
import java.util.UUID;

public interface FallIncidentService {
    FallIncidentResponseDTO processIncomingFallEvent(FallIncidentEventDTO eventDTO);
    FallIncidentResponseDTO dismissIncident(UUID incidentId, String note);
    FallIncidentResponseDTO acknowledgeIncident(UUID incidentId, UUID userId);
    FallIncidentResponseDTO resolveIncident(UUID incidentId, UUID userId, String note);
    List<FallIncidentResponseDTO> getActiveIncidents(UUID homeId);
}
