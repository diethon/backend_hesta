package com.hesta.backend.falldetection.controller;

import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.falldetection.dto.FallIncidentEventDTO;
import com.hesta.backend.falldetection.dto.FallIncidentResponseDTO;
import com.hesta.backend.falldetection.service.FallIncidentService;
import com.hesta.backend.repository.HomeMemberRepository;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.RealtimeSubscriptionService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping({"/api/v1/emergency/fall", "/api/emergency/fall"})
@RequiredArgsConstructor
public class FallIncidentController {

    private final FallIncidentService fallIncidentService;
    private final RealtimeSubscriptionService homeAccess;
    private final HomeMemberRepository homeMemberRepository;

    /** Public: pushed by the camera AI service, not by a logged-in user. */
    @PostMapping("/events")
    public ResponseEntity<FallIncidentResponseDTO> handleFallEvent(@RequestBody FallIncidentEventDTO eventDTO) {
        FallIncidentResponseDTO response = fallIncidentService.processIncomingFallEvent(eventDTO);
        return ResponseEntity.ok(response);
    }

    @PostMapping("/{id}/dismiss")
    public ResponseEntity<FallIncidentResponseDTO> dismissIncident(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID id,
            @RequestParam(required = false) String note,
            @RequestBody(required = false) java.util.Map<String, Object> body) {
        if (findAuthorizedIncident(user, id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        String resolvedNote = note;
        if (resolvedNote == null && body != null && body.get("note") != null) {
            resolvedNote = body.get("note").toString();
        }
        return okOrNotFound(fallIncidentService.dismissIncident(id, resolvedNote));
    }

    @PostMapping("/{id}/acknowledge")
    public ResponseEntity<FallIncidentResponseDTO> acknowledgeIncident(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID id) {
        if (findAuthorizedIncident(user, id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return okOrNotFound(fallIncidentService.acknowledgeIncident(id, user.getId()));
    }

    @PostMapping("/{id}/resolve")
    public ResponseEntity<FallIncidentResponseDTO> resolveIncident(
            @AuthenticationPrincipal CustomUserDetails user,
            @PathVariable UUID id,
            @RequestParam(required = false) String note) {
        if (findAuthorizedIncident(user, id).isEmpty()) {
            return ResponseEntity.notFound().build();
        }
        return okOrNotFound(fallIncidentService.resolveIncident(id, user.getId(), note));
    }

    @GetMapping("/active")
    public ResponseEntity<List<FallIncidentResponseDTO>> getActiveIncidents(
            @AuthenticationPrincipal CustomUserDetails user,
            @RequestParam(required = false) UUID homeId) {
        if (homeId != null) {
            homeAccess.authorizeSubscription(user.getId(), homeId);
            return ResponseEntity.ok(fallIncidentService.getActiveIncidents(homeId));
        }
        Set<UUID> myHomes = homeMemberRepository.findByUserId(user.getId()).stream()
                .filter(m -> m.getStatus() == MemberStatus.ACTIVE)
                .map(m -> m.getHome().getId())
                .collect(Collectors.toSet());
        return ResponseEntity.ok(fallIncidentService.getActiveIncidents(null).stream()
                .filter(i -> myHomes.contains(i.getHomeId()))
                .toList());
    }

    /** The active incident, if the caller is an active member of its home (throws 403 otherwise). */
    private Optional<FallIncidentResponseDTO> findAuthorizedIncident(CustomUserDetails user, UUID id) {
        Optional<FallIncidentResponseDTO> incident = fallIncidentService.getActiveIncidents(null).stream()
                .filter(i -> i.getId().equals(id))
                .findFirst();
        incident.ifPresent(i -> homeAccess.authorizeSubscription(user.getId(), i.getHomeId()));
        return incident;
    }

    private static ResponseEntity<FallIncidentResponseDTO> okOrNotFound(FallIncidentResponseDTO response) {
        return response != null ? ResponseEntity.ok(response) : ResponseEntity.notFound().build();
    }
}
