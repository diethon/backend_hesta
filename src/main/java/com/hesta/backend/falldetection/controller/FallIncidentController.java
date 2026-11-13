package com.hesta.backend.falldetection.controller;

import com.hesta.backend.falldetection.dto.FallIncidentEventDTO;
import com.hesta.backend.falldetection.dto.FallIncidentResponseDTO;
import com.hesta.backend.falldetection.service.FallIncidentService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping({"/api/v1/emergency/fall", "/api/emergency/fall"})
@RequiredArgsConstructor
public class FallIncidentController {

    private final FallIncidentService fallIncidentService;

    @PostMapping("/events")
    public ResponseEntity<FallIncidentResponseDTO> handleFallEvent(@RequestBody FallIncidentEventDTO eventDTO) {
        FallIncidentResponseDTO response = fallIncidentService.processIncomingFallEvent(eventDTO);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/dismiss")
    public ResponseEntity<FallIncidentResponseDTO> dismissIncident(
            @PathVariable UUID id,
            @RequestParam(required = false) String note,
            @RequestBody(required = false) java.util.Map<String, Object> body) {
        String resolvedNote = note;
        if (resolvedNote == null && body != null && body.get("note") != null) {
            resolvedNote = body.get("note").toString();
        }
        FallIncidentResponseDTO response = fallIncidentService.dismissIncident(id, resolvedNote);
        if (response != null) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<FallIncidentResponseDTO> acknowledgeIncident(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID userId) {
        FallIncidentResponseDTO response = fallIncidentService.acknowledgeIncident(id, userId != null ? userId : UUID.randomUUID());
        if (response != null) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.notFound().build();
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<FallIncidentResponseDTO> resolveIncident(
            @PathVariable UUID id,
            @RequestParam(required = false) UUID userId,
            @RequestParam(required = false) String note) {
        FallIncidentResponseDTO response = fallIncidentService.resolveIncident(id, userId != null ? userId : UUID.randomUUID(), note);
        if (response != null) {
            return ResponseEntity.ok(response);
        }
        return ResponseEntity.notFound().build();
    }

    @GetMapping("/active")
    public ResponseEntity<List<FallIncidentResponseDTO>> getActiveIncidents(
            @RequestParam(required = false) UUID homeId) {
        return ResponseEntity.ok(fallIncidentService.getActiveIncidents(homeId));
    }
}
