package com.hesta.backend.dto.contract;

import com.hesta.backend.contract.ContractTemplates;
import com.hesta.backend.entity.*;
import com.hesta.backend.enums.ContractActorRole;
import com.hesta.backend.enums.ContractEventType;
import com.hesta.backend.enums.InstallationContractStatus;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ContractResponses {

    private ContractResponses() {}

    public record PublicCatalogItem(
            UUID id,
            String name,
            String deviceType,
            String modelCode,
            String description,
            String imageUrl,
            String icon,
            BigDecimal unitPrice,
            Integer warrantyMonths
    ) {
        public static PublicCatalogItem from(DeviceCatalog c) {
            return new PublicCatalogItem(
                    c.getId(),
                    c.getName(),
                    c.getDeviceType(),
                    c.getModelCode(),
                    c.getDescription(),
                    c.getImageUrl(),
                    c.getIcon(),
                    c.getUnitPrice() != null ? c.getUnitPrice() : BigDecimal.ZERO,
                    c.getWarrantyMonths() != null ? c.getWarrantyMonths() : 12
            );
        }
    }

    public record BankInfoSnapshot(
            String providerCompanyName,
            String providerTaxCode,
            String providerAddress,
            String providerRepresentative,
            String providerHotline,
            String providerEmail,
            String bankName,
            String bankAccountNumber,
            String bankAccountHolder
    ) {}

    public record PublicShowcaseResponse(
            List<ContractTemplates.PackageSpec> packages,
            List<PublicCatalogItem> catalogItems,
            BankInfoSnapshot providerInfo
    ) {}

    public record PublicRequestCreatedResponse(
            UUID contractId,
            String code,
            String claimToken,
            InstallationContractStatus status,
            String customerEmail,
            boolean existingAccount
    ) {}

    public record ContractItemDto(
            UUID id,
            UUID catalogId,
            String deviceName,
            String modelCode,
            String deviceType,
            String roomLabel,
            Integer quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            Integer warrantyMonths,
            Integer sortOrder
    ) {
        public static ContractItemDto from(InstallationContractItem item) {
            return new ContractItemDto(
                    item.getId(),
                    item.getCatalog() != null ? item.getCatalog().getId() : null,
                    item.getDeviceName(),
                    item.getModelCode(),
                    item.getDeviceType(),
                    item.getRoomLabel(),
                    item.getQuantity(),
                    item.getUnitPrice(),
                    item.getLineTotal(),
                    item.getWarrantyMonths(),
                    item.getSortOrder()
            );
        }
    }

    public record ProvisionedDeviceDto(
            UUID id,
            UUID contractItemId,
            UUID deviceId,
            UUID roomId,
            String deviceName,
            String serialNumber,
            String roomName,
            Integer warrantyMonths,
            OffsetDateTime warrantyExpiresAt,
            OffsetDateTime provisionedAt
    ) {
        public static ProvisionedDeviceDto from(InstallationContractDevice d) {
            return new ProvisionedDeviceDto(
                    d.getId(),
                    d.getContractItem() != null ? d.getContractItem().getId() : null,
                    d.getDevice() != null ? d.getDevice().getId() : null,
                    d.getRoom() != null ? d.getRoom().getId() : null,
                    d.getDeviceName(),
                    d.getSerialNumber(),
                    d.getRoomName(),
                    d.getWarrantyMonths(),
                    d.getWarrantyExpiresAt(),
                    d.getProvisionedAt()
            );
        }
    }

    public record ContractEventDto(
            UUID id,
            UUID actorId,
            String actorName,
            ContractActorRole actorRole,
            ContractEventType eventType,
            InstallationContractStatus fromStatus,
            InstallationContractStatus toStatus,
            String message,
            Map<String, Object> metadata,
            OffsetDateTime createdAt
    ) {
        public static ContractEventDto from(InstallationContractEvent e) {
            return new ContractEventDto(
                    e.getId(),
                    e.getActor() != null ? e.getActor().getId() : null,
                    e.getActorName(),
                    e.getActorRole(),
                    e.getEventType(),
                    e.getFromStatus(),
                    e.getToStatus(),
                    e.getMessage(),
                    e.getMetadata(),
                    e.getCreatedAt()
            );
        }
    }

    public record ContractSummaryResponse(
            UUID id,
            String code,
            InstallationContractStatus status,
            Integer contractVersion,
            String customerName,
            String customerPhone,
            String customerEmail,
            String homeName,
            String homeType,
            String packageCode,
            UUID userId,
            UUID homeId,
            BigDecimal totalAmount,
            Integer depositPercent,
            BigDecimal depositAmount,
            OffsetDateTime scheduledInstallStart,
            OffsetDateTime userSignedAt,
            OffsetDateTime depositVerifiedAt,
            OffsetDateTime handedOverAt,
            int itemCount,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        public static ContractSummaryResponse from(InstallationContract c) {
            return new ContractSummaryResponse(
                    c.getId(),
                    c.getCode(),
                    c.getStatus(),
                    c.getContractVersion(),
                    c.getCustomerName(),
                    c.getCustomerPhone(),
                    c.getCustomerEmail(),
                    c.getHomeName(),
                    c.getHomeType(),
                    c.getPackageCode(),
                    c.getUser() != null ? c.getUser().getId() : null,
                    c.getHome() != null ? c.getHome().getId() : null,
                    c.getTotalAmount(),
                    c.getDepositPercent(),
                    c.getDepositAmount(),
                    c.getScheduledInstallStart(),
                    c.getUserSignedAt(),
                    c.getDepositVerifiedAt(),
                    c.getHandedOverAt(),
                    c.getItems() != null ? c.getItems().size() : 0,
                    c.getCreatedAt(),
                    c.getUpdatedAt()
            );
        }
    }

    public record ContractDetailResponse(
            UUID id,
            String code,
            InstallationContractStatus status,
            Integer contractVersion,
            String templateCode,
            UUID userId,
            UUID homeId,
            String linkedHomeName,
            String customerName,
            String customerPhone,
            String customerEmail,
            String installAddress,
            String homeName,
            String homeType,
            Integer roomCount,
            Integer floorCount,
            Integer bedroomCount,
            String buildingCondition,
            String desiredTimeline,
            List<Map<String, Object>> requestedDevices,
            List<Map<String, Object>> surveyChecklist,
            Map<String, Boolean> surveyFlags,
            String surveyNotes,
            OffsetDateTime surveyCompletedAt,
            String packageCode,
            String customerNote,
            BigDecimal subtotalAmount,
            BigDecimal installationFee,
            BigDecimal discountAmount,
            BigDecimal totalAmount,
            Integer depositPercent,
            BigDecimal depositAmount,
            BigDecimal remainingAmount,
            BankInfoSnapshot providerInfo,
            String depositTransferNote,
            String depositProofUrl,
            String depositReferenceCode,
            String depositNote,
            OffsetDateTime depositSubmittedAt,
            OffsetDateTime depositVerifiedAt,
            BigDecimal depositVerifiedAmount,
            OffsetDateTime preferredInstallDate,
            OffsetDateTime scheduledInstallStart,
            OffsetDateTime scheduledInstallEnd,
            String termsTitle,
            String termsContent,
            String specialTerms,
            String adminNote,
            String cancelReason,
            String userSignatureName,
            String userSignatureData,
            OffsetDateTime userSignedAt,
            String adminSignedName,
            OffsetDateTime adminSignedAt,
            String handoverMode,
            OffsetDateTime handedOverAt,
            String handoverNote,
            List<ContractItemDto> items,
            List<ProvisionedDeviceDto> provisionedDevices,
            List<ContractEventDto> events,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt
    ) {
        public static ContractDetailResponse from(InstallationContract c) {
            return new ContractDetailResponse(
                    c.getId(),
                    c.getCode(),
                    c.getStatus(),
                    c.getContractVersion(),
                    c.getTemplateCode(),
                    c.getUser() != null ? c.getUser().getId() : null,
                    c.getHome() != null ? c.getHome().getId() : null,
                    c.getHome() != null ? c.getHome().getName() : null,
                    c.getCustomerName(),
                    c.getCustomerPhone(),
                    c.getCustomerEmail(),
                    c.getInstallAddress(),
                    c.getHomeName(),
                    c.getHomeType(),
                    c.getRoomCount(),
                    c.getFloorCount(),
                    c.getBedroomCount(),
                    c.getBuildingCondition(),
                    c.getDesiredTimeline(),
                    c.getRequestedDevices() != null ? c.getRequestedDevices() : List.of(),
                    c.getSurveyChecklist() != null ? c.getSurveyChecklist() : List.of(),
                    c.getSurveyFlags() != null ? c.getSurveyFlags() : Map.of(),
                    c.getSurveyNotes(),
                    c.getSurveyCompletedAt(),
                    c.getPackageCode(),
                    c.getCustomerNote(),
                    c.getSubtotalAmount(),
                    c.getInstallationFee(),
                    c.getDiscountAmount(),
                    c.getTotalAmount(),
                    c.getDepositPercent(),
                    c.getDepositAmount(),
                    c.getRemainingAmount(),
                    new BankInfoSnapshot(
                            c.getProviderCompanyName(),
                            c.getProviderTaxCode(),
                            c.getProviderAddress(),
                            c.getProviderRepresentative(),
                            c.getProviderHotline(),
                            c.getProviderEmail(),
                            c.getBankName(),
                            c.getBankAccountNumber(),
                            c.getBankAccountHolder()
                    ),
                    c.getDepositTransferNote(),
                    c.getDepositProofUrl(),
                    c.getDepositReferenceCode(),
                    c.getDepositNote(),
                    c.getDepositSubmittedAt(),
                    c.getDepositVerifiedAt(),
                    c.getDepositVerifiedAmount(),
                    c.getPreferredInstallDate(),
                    c.getScheduledInstallStart(),
                    c.getScheduledInstallEnd(),
                    c.getTermsTitle(),
                    c.getTermsContent(),
                    c.getSpecialTerms(),
                    c.getAdminNote(),
                    c.getCancelReason(),
                    c.getUserSignatureName(),
                    c.getUserSignatureData(),
                    c.getUserSignedAt(),
                    c.getAdminSignedName(),
                    c.getAdminSignedAt(),
                    c.getHandoverMode(),
                    c.getHandedOverAt(),
                    c.getHandoverNote(),
                    c.getItems().stream().map(ContractItemDto::from).toList(),
                    c.getProvisionedDevices().stream().map(ProvisionedDeviceDto::from).toList(),
                    c.getEvents().stream().map(ContractEventDto::from).toList(),
                    c.getCreatedAt(),
                    c.getUpdatedAt()
            );
        }
    }

    public record HomeOptionDto(
            UUID id,
            String name,
            String address,
            List<String> roomNames
    ) {}

    public record UnclaimedDeviceOptionDto(
            UUID id,
            String name,
            String deviceType,
            String model,
            String serialNumber,
            String nodeCode,
            UUID catalogId
    ) {}

    public record ProvisionOptionsResponse(
            List<ContractTemplates.TemplateSpec> templates,
            List<PublicCatalogItem> catalogs,
            List<HomeOptionDto> existingHomes,
            List<UnclaimedDeviceOptionDto> unclaimedDevices,
            BankInfoSnapshot defaultProviderInfo
    ) {}

    public record AdminContractStatsResponse(
            long total,
            long submitted,
            long surveying,
            long quoted,
            long revisionRequested,
            long awaitingDeposit,
            long depositSubmitted,
            long depositConfirmed,
            long installing,
            long handedOver,
            long cancelled
    ) {}
}
