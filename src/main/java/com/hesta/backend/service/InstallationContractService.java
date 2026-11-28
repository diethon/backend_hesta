package com.hesta.backend.service;

import com.hesta.backend.dto.contract.ContractRequests.*;
import com.hesta.backend.dto.contract.ContractResponses.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

public interface InstallationContractService {

    // Public (Landing Page)
    PublicShowcaseResponse getPublicShowcase();

    PublicRequestCreatedResponse createPublicRequest(PublicInstallationRequest request);

    // User Portal
    ContractDetailResponse claimContract(UUID userId, String claimToken);

    List<ContractSummaryResponse> listMyContracts(UUID userId);

    ContractDetailResponse getMyContract(UUID userId, UUID contractId);

    ContractDetailResponse createUserRequest(UUID userId, PublicInstallationRequest request);

    ContractDetailResponse requestRevision(UUID userId, UUID contractId, RevisionRequest request);

    ContractDetailResponse addUserComment(UUID userId, UUID contractId, ContractCommentRequest request);

    ContractDetailResponse signContract(UUID userId, UUID contractId, String clientIp, SignContractRequest request);

    String uploadDepositProofImage(UUID userId, MultipartFile file);

    ContractDetailResponse submitDepositProof(UUID userId, UUID contractId, SubmitDepositProofRequest request);

    ContractDetailResponse cancelByUser(UUID userId, UUID contractId, CancelContractRequest request);

    // Admin Portal
    List<ContractSummaryResponse> listAllForAdmin(String status, String keyword);

    AdminContractStatsResponse getAdminStats();

    ContractDetailResponse getForAdmin(UUID contractId);

    ProvisionOptionsResponse getProvisionOptions();

    ContractDetailResponse updateSurvey(UUID adminId, UUID contractId, AdminSurveyUpdateRequest request);

    ContractDetailResponse saveProposal(UUID adminId, UUID contractId, AdminContractProposalRequest request);

    ContractDetailResponse addAdminComment(UUID adminId, UUID contractId, ContractCommentRequest request);

    ContractDetailResponse confirmDeposit(UUID adminId, UUID contractId, ConfirmDepositRequest request);

    ContractDetailResponse rejectDeposit(UUID adminId, UUID contractId, RejectDepositRequest request);

    ContractDetailResponse startInstallation(UUID adminId, UUID contractId, StartInstallationRequest request);

    ContractDetailResponse handover(UUID adminId, UUID contractId, HandoverContractRequest request);

    ContractDetailResponse cancelByAdmin(UUID adminId, UUID contractId, CancelContractRequest request);
}
