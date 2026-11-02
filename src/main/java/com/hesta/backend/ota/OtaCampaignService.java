package com.hesta.backend.ota;

import java.util.List;
import java.util.UUID;

public interface OtaCampaignService {

    FirmwarePackageDto registerPackage(FirmwarePackageDto dto);

    List<FirmwarePackageDto> listPackages();

    FirmwarePackageDto getPackage(UUID packageId);

    OtaCampaignDto createAndStartCampaign(CreateOtaCampaignRequest request);

    OtaCampaignDto getCampaign(UUID campaignId);

    List<OtaCampaignDto> listCampaigns();

    OtaCampaignDto abortCampaign(UUID campaignId, String reason);

    void handleNodeOtaResult(String nodeId, String version, boolean success, String error);
}
