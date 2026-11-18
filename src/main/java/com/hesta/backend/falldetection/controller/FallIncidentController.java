package com.hesta.backend.falldetection.controller;

import com.hesta.backend.enums.MemberStatus;
import com.hesta.backend.falldetection.dto.FallIncidentEventDTO;
import com.hesta.backend.falldetection.dto.FallIncidentResponseDTO;
import com.hesta.backend.falldetection.service.FallIncidentService;
import com.hesta.backend.repository.HomeMemberRepository;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.RealtimeSubscriptionService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping({"/api/v1/emergency/fall", "/api/emergency/fall"})
@RequiredArgsConstructor
public class FallIncidentController {

    private final FallIncidentService fallIncidentService;
    private final RealtimeSubscriptionService homeAccess;
    private final HomeMemberRepository homeMemberRepository;

    @Value("${hesta.internal.api-key:}")
    private String internalApiKey;

    /**
     * Pushed by internal Edge AI camera service.
     * When hesta.internal.api-key is configured, requests must provide matching X-Internal-Secret or X-API-Key header.
     */
    @PostMapping("/events")
    public ResponseEntity<FallIncidentResponseDTO> handleFallEvent(
            @RequestHeader(value = "X-Internal-Secret", required = false) String secretHeader,
            @RequestHeader(value = "X-API-Key", required = false) String apiKeyHeader,
            @RequestBody FallIncidentEventDTO eventDTO) {
        if (internalApiKey != null && !internalApiKey.isBlank()) {
            String token = secretHeader != null ? secretHeader : apiKeyHeader;
            if (!internalApiKey.equals(token)) {
                log.warn("[FALL CONTROLLER] Từ chối sự kiện té ngã: Thiếu hoặc sai internal API key");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
            }
        }
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
