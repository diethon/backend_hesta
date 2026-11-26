package com.hesta.backend.dto.contract;

import jakarta.validation.constraints.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class ContractRequests {

    private ContractRequests() {}

    public record DesiredDeviceItem(
            UUID catalogId,
            String deviceName,
            String roomLabel,
            Integer quantity
    ) {}

    public record RequestedDeviceNeed(
            String category,
            String label,
            List<String> locations,
            String estimatedQuantity,
            List<String> purposes,
            boolean needsConsultation,
            String note
    ) {}

    public record SurveyChecklistItem(
            String id,
            String label,
            String category,
            boolean confirmed
    ) {}

    public record PublicInstallationRequest(
            @NotBlank(message = "Họ tên không được để trống")
            @Size(max = 150)
            String customerName,

            @NotBlank(message = "Số điện thoại không được để trống")
            @Size(max = 30)
            String customerPhone,

            @NotBlank(message = "Email không được để trống")
            @Email(message = "Định dạng email không hợp lệ")
            @Size(max = 150)
            String customerEmail,

            @NotBlank(message = "Địa chỉ lắp đặt không được để trống")
            String installAddress,

            @Size(max = 150)
            String homeName,

            @Size(max = 30)
            String homeType,

            Integer roomCount,

            Integer floorCount,

            Integer bedroomCount,

            @Size(max = 40)
            String buildingCondition,

            @Size(max = 60)
            String desiredTimeline,

            @Size(max = 50)
            String packageCode,

            String customerNote,

            OffsetDateTime preferredInstallDate,

            List<DesiredDeviceItem> desiredItems,

            List<RequestedDeviceNeed> requestedDevices
    ) {}

    public record AdminSurveyUpdateRequest(
            Integer floorCount,
            Integer bedroomCount,
            String buildingCondition,
            String desiredTimeline,
            List<Map<String, Object>> requestedDevices,
            List<Map<String, Object>> surveyChecklist,
            Map<String, Boolean> surveyFlags,
            String surveyNotes,
            Boolean markCompleted
    ) {}

    public record ClaimContractRequest(
            @NotBlank(message = "Mã liên kết hợp đồng không được để trống")
            String claimToken
    ) {}

    public record ProposalItemInput(
            UUID catalogId,
            @NotBlank(message = "Tên thiết bị không được để trống")
            String deviceName,
            String modelCode,
            String deviceType,
            String roomLabel,
            @Min(1) @Max(200)
            Integer quantity,
            @DecimalMin("0")
            BigDecimal unitPrice,
            @Min(0) @Max(120)
            Integer warrantyMonths
    ) {}

    public record AdminContractProposalRequest(
            String templateCode,
            String termsTitle,
            String termsContent,
            String specialTerms,
            BigDecimal installationFee,
            BigDecimal discountAmount,
            @Min(0) @Max(100)
            Integer depositPercent,
            OffsetDateTime scheduledInstallStart,
            OffsetDateTime scheduledInstallEnd,
            String adminNote,
            String providerCompanyName,
            String providerTaxCode,
            String providerAddress,
            String providerRepresentative,
            String providerHotline,
            String providerEmail,
            String bankName,
            String bankAccountNumber,
            String bankAccountHolder,
            List<ProposalItemInput> items
    ) {}

    public record ContractCommentRequest(
            @NotBlank(message = "Nội dung trao đổi không được để trống")
            @Size(max = 2000)
            String message
    ) {}

    public record RevisionRequest(
            @NotBlank(message = "Vui lòng nêu rõ nội dung muốn điều chỉnh")
            @Size(max = 2000)
            String message
    ) {}

    public record SignContractRequest(
            @NotBlank(message = "Vui lòng nhập họ tên người ký")
            @Size(max = 150)
            String signerName,
            @NotBlank(message = "Vui lòng vẽ chữ ký điện tử")
            String signatureData,
            boolean acceptedTerms
    ) {}

    public record SubmitDepositProofRequest(
            @Size(max = 100)
            String referenceCode,
            String proofUrl,
            @Size(max = 1000)
            String note
    ) {}

    public record ConfirmDepositRequest(
            BigDecimal verifiedAmount,
            @Size(max = 100)
            String referenceCode,
            @Size(max = 1000)
            String note
    ) {}

    public record RejectDepositRequest(
            @NotBlank(message = "Vui lòng ghi rõ lý do cần kiểm tra lại tiền cọc")
            @Size(max = 1000)
            String reason
    ) {}

    public record StartInstallationRequest(
            @Size(max = 1000)
            String note
    ) {}

    public record CancelContractRequest(
            @Size(max = 1000)
            String reason
    ) {}

    public record HandoverDeviceInput(
            UUID contractItemId,
            UUID existingDeviceId,
            UUID catalogId,
            String deviceName,
            String roomName,
            String nodeCode,
            String localId,
            Integer warrantyMonths
    ) {}

    public record HandoverContractRequest(
            @NotBlank(message = "Vui lòng chọn phương án bàn giao")
            String mode,
            UUID existingHomeId,
            String homeName,
            String homeAddress,
            String handoverNote,
            List<HandoverDeviceInput> devices
    ) {}
}
