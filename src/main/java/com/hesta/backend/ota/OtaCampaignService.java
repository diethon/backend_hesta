package com.hesta.backend.ota;

import org.springframework.core.io.Resource;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface OtaCampaignService {

    record FirmwareDownloadPayload(Resource resource, FirmwarePackageDto metadata) {}

    FirmwarePackageDto uploadPackage(MultipartFile file, String deviceType, String version, String releaseNotes, String requestBaseUrl);

    FirmwarePackageDto registerPackage(FirmwarePackageDto dto);

    List<FirmwarePackageDto> listPackages();

    FirmwarePackageDto getPackage(UUID packageId);

    FirmwareDownloadPayload loadFirmwareForDownload(UUID packageId);

    OtaCampaignDto createAndStartCampaign(CreateOtaCampaignRequest request);

    OtaCampaignDto getCampaign(UUID campaignId);

    List<OtaCampaignDto> listCampaigns();

    OtaCampaignDto abortCampaign(UUID campaignId, String reason);

    void handleNodeOtaResult(String nodeId, String version, boolean success, String error);

    void handleNodeOtaResult(String nodeCode, String campaignId, String version, String status, Integer progressPct, String error);
}
