package com.hesta.backend.controller;

import com.hesta.backend.ota.OtaCampaignService;
import com.hesta.backend.security.policy.AccessPolicyType;
import com.hesta.backend.security.policy.HomeAccessPolicy;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/ota/packages")
@RequiredArgsConstructor
@HomeAccessPolicy(AccessPolicyType.PUBLIC)
public class OtaFirmwareDownloadController {

    private final OtaCampaignService otaCampaignService;

    @GetMapping("/{packageId}/download")
    public ResponseEntity<Resource> downloadFirmware(@PathVariable UUID packageId) {
        OtaCampaignService.FirmwareDownloadPayload payload = otaCampaignService.loadFirmwareForDownload(packageId);
        String version = payload.metadata().getVersion() != null
                ? payload.metadata().getVersion().replaceAll("[^a-zA-Z0-9._-]", "_")
                : "bin";
        String filename = "hesta-firmware-" + version + ".bin";

        ResponseEntity.BodyBuilder builder = ResponseEntity.ok()
                .contentType(MediaType.APPLICATION_OCTET_STREAM)
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + filename + "\"")
                .header("X-Firmware-Sha256", payload.metadata().getChecksumSha256());

        if (payload.metadata().getFileSizeBytes() > 0) {
            builder.contentLength(payload.metadata().getFileSizeBytes());
        }

        return builder.body(payload.resource());
    }
}
