package com.hesta.backend.service.impl;

import com.hesta.backend.contract.ContractTemplates;
import com.hesta.backend.dto.contract.ContractRequests.*;
import com.hesta.backend.dto.contract.ContractResponses.*;
import com.hesta.backend.entity.*;
import com.hesta.backend.enums.*;
import com.hesta.backend.event.ContractNotificationEvent;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.*;
import com.hesta.backend.service.CloudinaryService;
import com.hesta.backend.service.InstallationContractService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InstallationContractServiceImpl implements InstallationContractService {

    private static final SecureRandom SECURE_RANDOM = new SecureRandom();
    private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Ho_Chi_Minh");
    private static final DateTimeFormatter CODE_YEAR = DateTimeFormatter.ofPattern("yyyy");
    private static final int CLAIM_TOKEN_DAYS = 14;

    private final InstallationContractRepository contractRepository;
    private final DeviceCatalogRepository catalogRepository;
    private final DeviceRepository deviceRepository;
    private final DeviceRegistrationTokenRepository tokenRepository;
    private final EdgeNodeRepository edgeNodeRepository;
    private final HomeRepository homeRepository;
    private final HomeMemberRepository homeMemberRepository;
    private final RoomRepository roomRepository;
    private final UserRepository userRepository;
    private final CloudinaryService cloudinaryService;
    private final ApplicationEventPublisher eventPublisher;

    // =========================================================================
    // Public (Landing Page)
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public PublicShowcaseResponse getPublicShowcase() {
        List<DeviceCatalog> activeCatalogs = catalogRepository.findAllByIsActiveTrueOrderByCreatedAtDesc();
        List<PublicCatalogItem> catalogItems = activeCatalogs.stream()
                .map(PublicCatalogItem::from)
                .toList();
        // Compute live estimatedPrice from real DeviceCatalog prices so Landing Page == Contract 100%
        List<ContractTemplates.PackageSpec> livePackages = ContractTemplates.PACKAGES.stream()
                .map(pkg -> {
                    if (pkg.bomItems().isEmpty()) return pkg;
                    BigDecimal computed = BigDecimal.ZERO;
                    for (ContractTemplates.PackageBomItem bom : pkg.bomItems()) {
                        DeviceCatalog matched = findCatalogForBom(activeCatalogs, bom);
                        if (matched != null && matched.getUnitPrice() != null) {
                            computed = computed.add(matched.getUnitPrice().multiply(BigDecimal.valueOf(bom.quantity())));
                        }
                    }
                    BigDecimal finalEstimate = computed.compareTo(BigDecimal.ZERO) > 0 ? computed : pkg.estimatedPrice();
                    return new ContractTemplates.PackageSpec(
                            pkg.code(), pkg.name(), pkg.nameEn(), pkg.subtitle(), pkg.subtitleEn(),
                            pkg.badge(), pkg.badgeEn(), pkg.templateCode(),
                            finalEstimate, pkg.installationFee(),
                            pkg.highlights(), pkg.highlightsEn(), pkg.bomItems()
                    );
                })
                .toList();
        return new PublicShowcaseResponse(
                livePackages,
                catalogItems,
                defaultProviderSnapshot()
        );
    }

    @Override
    @Transactional
    public PublicRequestCreatedResponse createPublicRequest(PublicInstallationRequest request) {
        InstallationContract contract = buildInitialContract(request, null);
        String rawClaimToken = generateHexToken(24);
        contract.setClaimToken(rawClaimToken);
        contract.setClaimTokenExpiresAt(OffsetDateTime.now().plusDays(CLAIM_TOKEN_DAYS));

        Optional<User> existingUser = userRepository.findByEmail(contract.getCustomerEmail());
        existingUser.ifPresent(contract::setUser);

        contract.addEvent(InstallationContractEvent.builder()
                .actor(existingUser.orElse(null))
                .actorName(contract.getCustomerName())
                .actorRole(ContractActorRole.CUSTOMER)
                .eventType(ContractEventType.SUBMITTED)
                .toStatus(InstallationContractStatus.SUBMITTED)
                .message(buildSubmissionMessage(contract))
                .build());

        InstallationContract saved = contractRepository.save(contract);

        notifyAdmins(
                "Yêu cầu lắp đặt mới " + saved.getCode(),
                String.format("Khách hàng %s (%s) vừa gửi yêu cầu lắp đặt từ Landing Page.",
                        saved.getCustomerName(), saved.getCustomerPhone()),
                NotificationPriority.HIGH
        );

        log.info("Created public installation contract request [{}] for email [{}]",
                saved.getCode(), saved.getCustomerEmail());

        return new PublicRequestCreatedResponse(
                saved.getId(),
                saved.getCode(),
                rawClaimToken,
                saved.getStatus(),
                saved.getCustomerEmail(),
                existingUser.isPresent()
        );
    }

    // =========================================================================
    // User Portal
    // =========================================================================

    @Override
    @Transactional
    public ContractDetailResponse claimContract(UUID userId, String claimToken) {
        if (claimToken == null || claimToken.isBlank()) {
            throw new AppException(ErrorCode.CONTRACT_CLAIM_TOKEN_INVALID);
        }
        User user = requireUser(userId);
        InstallationContract contract = contractRepository.findByClaimToken(claimToken.trim())
                .orElseThrow(() -> new AppException(ErrorCode.CONTRACT_CLAIM_TOKEN_INVALID));

        if (contract.getClaimTokenExpiresAt() != null
                && OffsetDateTime.now().isAfter(contract.getClaimTokenExpiresAt())) {
            throw new AppException(ErrorCode.CONTRACT_CLAIM_TOKEN_INVALID);
        }
        if (contract.getUser() != null && !contract.getUser().getId().equals(userId)) {
            throw new AppException(ErrorCode.CONTRACT_ALREADY_CLAIMED);
        }

        contract.setUser(user);
        contract.setClaimToken(null);
        contract.setClaimTokenExpiresAt(null);
        contract.addEvent(InstallationContractEvent.builder()
                .actor(user)
                .actorName(resolveDisplayName(user))
                .actorRole(ContractActorRole.CUSTOMER)
                .eventType(ContractEventType.CLAIMED)
                .fromStatus(contract.getStatus())
                .toStatus(contract.getStatus())
                .message("Khách hàng đã liên kết hồ sơ hợp đồng với tài khoản HESTA.")
                .build());

        InstallationContract saved = contractRepository.save(contract);
        return ContractDetailResponse.from(saved);
    }

    @Override
    @Transactional
    public List<ContractSummaryResponse> listMyContracts(UUID userId) {
        User user = requireUser(userId);
        List<InstallationContract> contracts = contractRepository.findAccessibleByUser(userId, user.getEmail());
        // Auto-attach unowned contracts matching the verified user email
        boolean dirty = false;
        for (InstallationContract c : contracts) {
            if (c.getUser() == null && c.getCustomerEmail().equalsIgnoreCase(user.getEmail())) {
                c.setUser(user);
                dirty = true;
            }
        }
        if (dirty) {
            contractRepository.saveAll(contracts);
        }
        return contracts.stream().map(ContractSummaryResponse::from).toList();
    }

    @Override
    @Transactional
    public ContractDetailResponse getMyContract(UUID userId, UUID contractId) {
        User user = requireUser(userId);
        InstallationContract contract = requireCustomerContract(user, contractId);
        return ContractDetailResponse.from(contract);
    }

    @Override
    @Transactional
    public ContractDetailResponse createUserRequest(UUID userId, PublicInstallationRequest request) {
        User user = requireUser(userId);
        InstallationContract contract = buildInitialContract(request, user);
        contract.addEvent(InstallationContractEvent.builder()
                .actor(user)
                .actorName(resolveDisplayName(user))
                .actorRole(ContractActorRole.CUSTOMER)
                .eventType(ContractEventType.SUBMITTED)
                .toStatus(InstallationContractStatus.SUBMITTED)
                .message(buildSubmissionMessage(contract))
                .build());

        InstallationContract saved = contractRepository.save(contract);

        notifyAdmins(
                "Yêu cầu lắp đặt mới " + saved.getCode(),
                String.format("Khách hàng %s vừa tạo yêu cầu hợp đồng lắp đặt mới.", resolveDisplayName(user)),
                NotificationPriority.HIGH
        );

        return ContractDetailResponse.from(saved);
    }

    @Override
    @Transactional
    public ContractDetailResponse requestRevision(UUID userId, UUID contractId, RevisionRequest request) {
        User user = requireUser(userId);
        InstallationContract contract = requireCustomerContract(user, contractId);
        if (contract.getStatus() != InstallationContractStatus.QUOTED) {
            throw new AppException(ErrorCode.CONTRACT_INVALID_STATUS);
        }

        InstallationContractStatus prev = contract.getStatus();
        contract.setStatus(InstallationContractStatus.REVISION_REQUESTED);
        contract.addEvent(InstallationContractEvent.builder()
                .actor(user)
                .actorName(resolveDisplayName(user))
                .actorRole(ContractActorRole.CUSTOMER)
                .eventType(ContractEventType.REVISION_REQUESTED)
                .fromStatus(prev)
                .toStatus(InstallationContractStatus.REVISION_REQUESTED)
                .message(request.message().trim())
                .build());

        InstallationContract saved = contractRepository.save(contract);

        notifyAdmins(
                "Khách yêu cầu điều chỉnh " + saved.getCode(),
                String.format("%s gửi yêu cầu điều chỉnh: %s", resolveDisplayName(user), truncate(request.message(), 120)),
                NotificationPriority.HIGH
        );

        return ContractDetailResponse.from(saved);
    }

    @Override
    @Transactional
    public ContractDetailResponse addUserComment(UUID userId, UUID contractId, ContractCommentRequest request) {
        User user = requireUser(userId);
        InstallationContract contract = requireCustomerContract(user, contractId);

        contract.addEvent(InstallationContractEvent.builder()
                .actor(user)
                .actorName(resolveDisplayName(user))
                .actorRole(ContractActorRole.CUSTOMER)
                .eventType(ContractEventType.COMMENT)
                .fromStatus(contract.getStatus())
                .toStatus(contract.getStatus())
                .message(request.message().trim())
                .build());

        InstallationContract saved = contractRepository.save(contract);

        notifyAdmins(
                "Tin nhắn mới tại hợp đồng " + saved.getCode(),
                String.format("%s: %s", resolveDisplayName(user), truncate(request.message(), 120)),
                NotificationPriority.MEDIUM
        );

        return ContractDetailResponse.from(saved);
    }

    @Override
    @Transactional
    public ContractDetailResponse signContract(UUID userId, UUID contractId, String clientIp, SignContractRequest request) {
        User user = requireUser(userId);
        InstallationContract contract = requireCustomerContract(user, contractId);
        if (contract.getStatus() != InstallationContractStatus.QUOTED) {
            throw new AppException(ErrorCode.CONTRACT_INVALID_STATUS);
        }
        if (!request.acceptedTerms()) {
            throw new AppException(ErrorCode.CONTRACT_INVALID_REQUEST);
        }
        if (contract.getItems().isEmpty()) {
            throw new AppException(ErrorCode.CONTRACT_INVALID_REQUEST);
        }

        InstallationContractStatus prev = contract.getStatus();
        OffsetDateTime now = OffsetDateTime.now();

        contract.setUserSignatureName(request.signerName().trim());
        contract.setUserSignatureData(request.signatureData().trim());
        contract.setUserSignedAt(now);
        contract.setUserSignedIp(clientIp != null && clientIp.length() > 64 ? clientIp.substring(0, 64) : clientIp);

        // If deposit is 0%, move straight to DEPOSIT_CONFIRMED; otherwise AWAITING_DEPOSIT
        InstallationContractStatus nextStatus = (contract.getDepositAmount() == null
                || contract.getDepositAmount().compareTo(BigDecimal.ZERO) <= 0)
                ? InstallationContractStatus.DEPOSIT_CONFIRMED
                : InstallationContractStatus.AWAITING_DEPOSIT;
        contract.setStatus(nextStatus);

        contract.addEvent(InstallationContractEvent.builder()
                .actor(user)
                .actorName(request.signerName().trim())
                .actorRole(ContractActorRole.CUSTOMER)
                .eventType(ContractEventType.SIGNED)
                .fromStatus(prev)
                .toStatus(nextStatus)
                .message(String.format("Khách hàng %s đã đồng ý toàn bộ điều khoản và ký hợp đồng điện tử phiên bản v%d.",
                        request.signerName().trim(), contract.getContractVersion()))
                .metadata(Map.of(
                        "contractVersion", contract.getContractVersion(),
                        "totalAmount", contract.getTotalAmount(),
                        "depositAmount", contract.getDepositAmount()
                ))
                .build());

        InstallationContract saved = contractRepository.save(contract);

        notifyAdmins(
                "Hợp đồng " + saved.getCode() + " đã được ký!",
                String.format("Khách hàng %s đã ký hợp đồng trị giá %s đ. Đang chờ chuyển khoản đặt cọc.",
                        saved.getUserSignatureName(), formatVnd(saved.getTotalAmount())),
                NotificationPriority.HIGH
        );

        return ContractDetailResponse.from(saved);
    }

    @Override
    public String uploadDepositProofImage(UUID userId, MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new AppException(ErrorCode.CONTRACT_INVALID_REQUEST);
        }
        try {
            return cloudinaryService.uploadImage(file, "contract-deposits");
        } catch (Exception ex) {
            log.error("Failed to upload deposit proof image for user {}", userId, ex);
            throw new AppException(ErrorCode.AVATAR_UPLOAD_FAILED);
        }
    }

    @Override
    @Transactional
    public ContractDetailResponse submitDepositProof(UUID userId, UUID contractId, SubmitDepositProofRequest request) {
        User user = requireUser(userId);
        InstallationContract contract = requireCustomerContract(user, contractId);
        if (contract.getStatus() != InstallationContractStatus.AWAITING_DEPOSIT
                && contract.getStatus() != InstallationContractStatus.DEPOSIT_SUBMITTED) {
            throw new AppException(ErrorCode.CONTRACT_INVALID_STATUS);
        }

        InstallationContractStatus prev = contract.getStatus();
        contract.setDepositReferenceCode(trimToNull(request.referenceCode()));
        contract.setDepositProofUrl(trimToNull(request.proofUrl()));
        contract.setDepositNote(trimToNull(request.note()));
        contract.setDepositSubmittedAt(OffsetDateTime.now());
        contract.setStatus(InstallationContractStatus.DEPOSIT_SUBMITTED);

        StringBuilder msg = new StringBuilder("Khách hàng xác nhận đã chuyển khoản đặt cọc");
        if (contract.getDepositReferenceCode() != null) {
            msg.append(" (Mã GD: ").append(contract.getDepositReferenceCode()).append(")");
        }
        if (contract.getDepositNote() != null) {
            msg.append(" — ").append(contract.getDepositNote());
        }

        contract.addEvent(InstallationContractEvent.builder()
                .actor(user)
                .actorName(resolveDisplayName(user))
                .actorRole(ContractActorRole.CUSTOMER)
                .eventType(ContractEventType.DEPOSIT_SUBMITTED)
                .fromStatus(prev)
                .toStatus(InstallationContractStatus.DEPOSIT_SUBMITTED)
                .message(msg.toString())
                .build());

        InstallationContract saved = contractRepository.save(contract);

        notifyAdmins(
                "Chờ đối soát đặt cọc " + saved.getCode(),
                String.format("%s báo đã chuyển khoản cọc %s đ. Vui lòng kiểm tra tài khoản doanh nghiệp và xác nhận.",
                        resolveDisplayName(user), formatVnd(saved.getDepositAmount())),
                NotificationPriority.HIGH
        );

        return ContractDetailResponse.from(saved);
    }

    @Override
    @Transactional
    public ContractDetailResponse cancelByUser(UUID userId, UUID contractId, CancelContractRequest request) {
        User user = requireUser(userId);
        InstallationContract contract = requireCustomerContract(user, contractId);
        if (!InstallationContractStatus.CANCELLABLE.contains(contract.getStatus())) {
            throw new AppException(ErrorCode.CONTRACT_INVALID_STATUS);
        }

        InstallationContractStatus prev = contract.getStatus();
        String reason = request != null ? trimToNull(request.reason()) : null;
        contract.setStatus(InstallationContractStatus.CANCELLED);
        contract.setCancelReason(reason != null ? reason : "Khách hàng hủy yêu cầu");

        contract.addEvent(InstallationContractEvent.builder()
                .actor(user)
                .actorName(resolveDisplayName(user))
                .actorRole(ContractActorRole.CUSTOMER)
                .eventType(ContractEventType.CANCELLED)
                .fromStatus(prev)
                .toStatus(InstallationContractStatus.CANCELLED)
                .message("Khách hàng hủy hợp đồng: " + contract.getCancelReason())
                .build());

        InstallationContract saved = contractRepository.save(contract);
        notifyAdmins("Hợp đồng " + saved.getCode() + " đã bị hủy", contract.getCancelReason(), NotificationPriority.MEDIUM);
        return ContractDetailResponse.from(saved);
    }

    // =========================================================================
    // Admin Portal
    // =========================================================================

    @Override
    @Transactional(readOnly = true)
    public List<ContractSummaryResponse> listAllForAdmin(String status, String keyword) {
        InstallationContractStatus parsedStatus = null;
        if (status != null && !status.isBlank() && !"ALL".equalsIgnoreCase(status.trim())) {
            try {
                parsedStatus = InstallationContractStatus.valueOf(status.trim().toUpperCase());
            } catch (IllegalArgumentException e) {
                throw new AppException(ErrorCode.CONTRACT_INVALID_REQUEST);
            }
        }
        String pattern = (keyword != null && !keyword.isBlank())
                ? "%" + keyword.trim().toLowerCase() + "%"
                : null;
        return contractRepository.searchForAdmin(parsedStatus, pattern)
                .stream()
                .map(ContractSummaryResponse::from)
                .toList();
    }

    @Override
    @Transactional(readOnly = true)
    public AdminContractStatsResponse getAdminStats() {
        return new AdminContractStatsResponse(
                contractRepository.count(),
                contractRepository.countByStatus(InstallationContractStatus.SUBMITTED),
                contractRepository.countByStatus(InstallationContractStatus.SURVEYING),
                contractRepository.countByStatus(InstallationContractStatus.QUOTED),
                contractRepository.countByStatus(InstallationContractStatus.REVISION_REQUESTED),
                contractRepository.countByStatus(InstallationContractStatus.AWAITING_DEPOSIT),
                contractRepository.countByStatus(InstallationContractStatus.DEPOSIT_SUBMITTED),
                contractRepository.countByStatus(InstallationContractStatus.DEPOSIT_CONFIRMED),
                contractRepository.countByStatus(InstallationContractStatus.INSTALLING),
                contractRepository.countByStatus(InstallationContractStatus.HANDED_OVER),
                contractRepository.countByStatus(InstallationContractStatus.CANCELLED)
        );
    }

    @Override
    @Transactional
    public ContractDetailResponse updateSurvey(UUID adminId, UUID contractId, AdminSurveyUpdateRequest request) {
        User admin = requireUser(adminId);
        InstallationContract contract = requireContract(contractId);

        if (InstallationContractStatus.LOCKED_TERMS.contains(contract.getStatus())) {
            throw new AppException(ErrorCode.CONTRACT_LOCKED_AFTER_SIGN);
        }
        if (!InstallationContractStatus.EDITABLE_PROPOSAL.contains(contract.getStatus())) {
            throw new AppException(ErrorCode.CONTRACT_INVALID_STATUS);
        }

        InstallationContractStatus prev = contract.getStatus();
        if (request.floorCount() != null && request.floorCount() > 0) {
            contract.setFloorCount(request.floorCount());
        }
        if (request.bedroomCount() != null && request.bedroomCount() >= 0) {
            contract.setBedroomCount(request.bedroomCount());
        }
        if (trimToNull(request.buildingCondition()) != null) {
            contract.setBuildingCondition(request.buildingCondition().trim().toUpperCase());
        }
        if (trimToNull(request.desiredTimeline()) != null) {
            contract.setDesiredTimeline(request.desiredTimeline().trim());
        }
        if (request.requestedDevices() != null) {
            contract.setRequestedDevices(new ArrayList<>(request.requestedDevices()));
        }
        if (request.surveyChecklist() != null) {
            contract.setSurveyChecklist(new ArrayList<>(request.surveyChecklist()));
        }
        if (request.surveyFlags() != null) {
            contract.setSurveyFlags(new HashMap<>(request.surveyFlags()));
        }
        contract.setSurveyNotes(trimToNull(request.surveyNotes()));

        boolean allCoreConfirmed = Boolean.TRUE.equals(request.markCompleted())
                || (Boolean.TRUE.equals(contract.getSurveyFlags().get("quantityConfirmed"))
                && Boolean.TRUE.equals(contract.getSurveyFlags().get("locationConfirmed"))
                && Boolean.TRUE.equals(contract.getSurveyFlags().get("compatibilityConfirmed")));
        if (allCoreConfirmed && contract.getSurveyCompletedAt() == null) {
            contract.setSurveyCompletedAt(OffsetDateTime.now());
        }

        if (prev == InstallationContractStatus.SUBMITTED) {
            contract.setStatus(InstallationContractStatus.SURVEYING);
        }

        long confirmedChecklist = contract.getSurveyChecklist().stream()
                .filter(m -> Boolean.TRUE.equals(m.get("confirmed")))
                .count();
        String msg = String.format("Kỹ thuật viên HESTA đã cập nhật hồ sơ khảo sát (%d/%d hạng mục đã xác nhận).",
                confirmedChecklist, contract.getSurveyChecklist().size());
        if (contract.getSurveyNotes() != null) {
            msg += " Ghi chú kỹ thuật: " + contract.getSurveyNotes();
        }

        contract.addEvent(InstallationContractEvent.builder()
                .actor(admin)
                .actorName(resolveDisplayName(admin))
                .actorRole(ContractActorRole.ADMIN)
                .eventType(ContractEventType.SURVEY_UPDATED)
                .fromStatus(prev)
                .toStatus(contract.getStatus())
                .message(msg)
                .build());

        InstallationContract saved = contractRepository.save(contract);

        notifyCustomer(
                saved,
                "Cập nhật tiến độ khảo sát — " + saved.getCode(),
                msg,
                NotificationPriority.MEDIUM
        );

        return ContractDetailResponse.from(saved);
    }

    @Override
    @Transactional(readOnly = true)
    public ContractDetailResponse getForAdmin(UUID contractId) {
        InstallationContract contract = requireContract(contractId);
        return ContractDetailResponse.from(contract);
    }

    @Override
    @Transactional(readOnly = true)
    public ProvisionOptionsResponse getProvisionOptions() {
        List<PublicCatalogItem> catalogs = catalogRepository.findAllByIsActiveTrueOrderByCreatedAtDesc()
                .stream()
                .map(PublicCatalogItem::from)
                .toList();

        List<HomeOptionDto> homes = homeRepository.findAll().stream()
                .map(h -> new HomeOptionDto(
                        h.getId(),
                        h.getName(),
                        h.getAddress(),
                        roomRepository.findByHomeId(h.getId()).stream().map(Room::getName).toList()
                ))
                .toList();

        List<UnclaimedDeviceOptionDto> unclaimed = deviceRepository
                .findAllByStatusWithCatalogAndNode(DeviceStatus.UNCLAIMED)
                .stream()
                .map(d -> new UnclaimedDeviceOptionDto(
                        d.getId(),
                        d.getName(),
                        d.getDeviceType(),
                        d.getModel(),
                        d.getSerialNumber(),
                        d.getNode() != null ? d.getNode().getNodeCode() : null,
                        d.getCatalog() != null ? d.getCatalog().getId() : null
                ))
                .toList();

        return new ProvisionOptionsResponse(
                ContractTemplates.TEMPLATES,
                catalogs,
                homes,
                unclaimed,
                defaultProviderSnapshot()
        );
    }

    @Override
    @Transactional
    public ContractDetailResponse saveProposal(UUID adminId, UUID contractId, AdminContractProposalRequest request) {
        User admin = requireUser(adminId);
        InstallationContract contract = requireContract(contractId);

        if (InstallationContractStatus.LOCKED_TERMS.contains(contract.getStatus())) {
            throw new AppException(ErrorCode.CONTRACT_LOCKED_AFTER_SIGN);
        }
        if (!InstallationContractStatus.EDITABLE_PROPOSAL.contains(contract.getStatus())) {
            throw new AppException(ErrorCode.CONTRACT_INVALID_STATUS);
        }
        if (request.items() == null || request.items().isEmpty()) {
            throw new AppException(ErrorCode.CONTRACT_INVALID_REQUEST);
        }

        InstallationContractStatus prev = contract.getStatus();
        ContractTemplates.TemplateSpec tpl = ContractTemplates.findTemplateOrDefault(request.templateCode());

        contract.setTemplateCode(tpl.code());
        contract.setTermsTitle(nonBlankOr(request.termsTitle(), tpl.termsTitle()));
        contract.setTermsContent(nonBlankOr(request.termsContent(), tpl.termsContent()));
        contract.setSpecialTerms(trimToNull(request.specialTerms()));
        contract.setAdminNote(trimToNull(request.adminNote()));

        // Provider & bank info (optional override per contract)
        contract.setProviderCompanyName(nonBlankOr(request.providerCompanyName(), contract.getProviderCompanyName()));
        contract.setProviderTaxCode(nonBlankOr(request.providerTaxCode(), contract.getProviderTaxCode()));
        contract.setProviderAddress(nonBlankOr(request.providerAddress(), contract.getProviderAddress()));
        contract.setProviderRepresentative(nonBlankOr(request.providerRepresentative(), contract.getProviderRepresentative()));
        contract.setProviderHotline(nonBlankOr(request.providerHotline(), contract.getProviderHotline()));
        contract.setProviderEmail(nonBlankOr(request.providerEmail(), contract.getProviderEmail()));
        contract.setBankName(nonBlankOr(request.bankName(), contract.getBankName()));
        contract.setBankAccountNumber(nonBlankOr(request.bankAccountNumber(), contract.getBankAccountNumber()));
        contract.setBankAccountHolder(nonBlankOr(request.bankAccountHolder(), contract.getBankAccountHolder()));

        // Schedule
        contract.setScheduledInstallStart(request.scheduledInstallStart());
        contract.setScheduledInstallEnd(request.scheduledInstallEnd());

        // Items
        List<InstallationContractItem> newItems = new ArrayList<>();
        BigDecimal subtotal = BigDecimal.ZERO;
        for (ProposalItemInput input : request.items()) {
            DeviceCatalog catalog = input.catalogId() != null
                    ? catalogRepository.findById(input.catalogId()).orElse(null)
                    : null;
            int qty = input.quantity() != null && input.quantity() > 0 ? input.quantity() : 1;
            BigDecimal unitPrice = input.unitPrice() != null
                    ? input.unitPrice().max(BigDecimal.ZERO)
                    : (catalog != null && catalog.getUnitPrice() != null ? catalog.getUnitPrice() : BigDecimal.ZERO);
            BigDecimal lineTotal = unitPrice.multiply(BigDecimal.valueOf(qty)).setScale(2, RoundingMode.HALF_UP);
            int warranty = input.warrantyMonths() != null
                    ? input.warrantyMonths()
                    : (catalog != null && catalog.getWarrantyMonths() != null ? catalog.getWarrantyMonths() : 12);

            newItems.add(InstallationContractItem.builder()
                    .catalog(catalog)
                    .deviceName(input.deviceName().trim())
                    .modelCode(nonBlankOr(input.modelCode(), catalog != null ? catalog.getModelCode() : null))
                    .deviceType(nonBlankOr(input.deviceType(), catalog != null ? catalog.getDeviceType() : "OTHER"))
                    .roomLabel(nonBlankOr(input.roomLabel(), "Phòng khách"))
                    .quantity(qty)
                    .unitPrice(unitPrice.setScale(2, RoundingMode.HALF_UP))
                    .lineTotal(lineTotal)
                    .warrantyMonths(warranty)
                    .build());
            subtotal = subtotal.add(lineTotal);
        }
        contract.replaceItems(newItems);

        BigDecimal installFee = request.installationFee() != null
                ? request.installationFee().max(BigDecimal.ZERO)
                : BigDecimal.ZERO;
        BigDecimal discount = request.discountAmount() != null
                ? request.discountAmount().max(BigDecimal.ZERO)
                : BigDecimal.ZERO;
        BigDecimal total = subtotal.add(installFee).subtract(discount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        int depositPct = request.depositPercent() != null ? Math.max(0, Math.min(100, request.depositPercent())) : 30;
        BigDecimal depositAmount = total.multiply(BigDecimal.valueOf(depositPct))
                .divide(BigDecimal.valueOf(100), 0, RoundingMode.HALF_UP)
                .setScale(2, RoundingMode.HALF_UP);
        BigDecimal remaining = total.subtract(depositAmount).max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP);

        contract.setSubtotalAmount(subtotal.setScale(2, RoundingMode.HALF_UP));
        contract.setInstallationFee(installFee.setScale(2, RoundingMode.HALF_UP));
        contract.setDiscountAmount(discount.setScale(2, RoundingMode.HALF_UP));
        contract.setTotalAmount(total);
        contract.setDepositPercent(depositPct);
        contract.setDepositAmount(depositAmount);
        contract.setRemainingAmount(remaining);

        // Upgrade consultation request code YC-... -> official contract code HD-... upon first official quotation
        if (contract.getCode() != null && contract.getCode().startsWith("YC-")) {
            String upgraded = "HD-" + contract.getCode().substring(3);
            if (!contractRepository.existsByCode(upgraded)) {
                contract.setCode(upgraded);
            }
        }
        contract.setDepositTransferNote("HESTA COC " + contract.getCode());

        Map<String, Boolean> flags = contract.getSurveyFlags() != null
                ? new HashMap<>(contract.getSurveyFlags())
                : new HashMap<>();
        flags.put("quoteReady", true);
        contract.setSurveyFlags(flags);
        if (contract.getSurveyCompletedAt() == null) {
            contract.setSurveyCompletedAt(OffsetDateTime.now());
        }

        if (prev == InstallationContractStatus.QUOTED || prev == InstallationContractStatus.REVISION_REQUESTED) {
            contract.setContractVersion(contract.getContractVersion() + 1);
        }
        contract.setStatus(InstallationContractStatus.QUOTED);
        contract.setAdminSignedBy(admin);
        contract.setAdminSignedName(resolveDisplayName(admin));
        contract.setAdminSignedAt(OffsetDateTime.now());

        String summaryMsg = String.format(
                "HESTA đã gửi bản thảo Hợp đồng v%d (%d hạng mục thiết bị, tổng giá trị %s đ, đặt cọc %d%% = %s đ).",
                contract.getContractVersion(), newItems.size(), formatVnd(total), depositPct, formatVnd(depositAmount));
        if (contract.getAdminNote() != null) {
            summaryMsg += " Ghi chú: " + contract.getAdminNote();
        }

        contract.addEvent(InstallationContractEvent.builder()
                .actor(admin)
                .actorName(resolveDisplayName(admin))
                .actorRole(ContractActorRole.ADMIN)
                .eventType(ContractEventType.QUOTE_SENT)
                .fromStatus(prev)
                .toStatus(InstallationContractStatus.QUOTED)
                .message(summaryMsg)
                .metadata(Map.of(
                        "contractVersion", contract.getContractVersion(),
                        "totalAmount", total,
                        "depositPercent", depositPct,
                        "depositAmount", depositAmount
                ))
                .build());

        InstallationContract saved = contractRepository.save(contract);

        notifyCustomer(
                saved,
                "Hợp đồng " + saved.getCode() + " đã có báo giá (v" + saved.getContractVersion() + ")",
                String.format("Tổng giá trị: %s đ (cọc %d%%). Vui lòng xem chi tiết, thương lượng hoặc ký điện tử.",
                        formatVnd(total), depositPct),
                NotificationPriority.HIGH
        );

        return ContractDetailResponse.from(saved);
    }

    @Override
    @Transactional
    public ContractDetailResponse addAdminComment(UUID adminId, UUID contractId, ContractCommentRequest request) {
        User admin = requireUser(adminId);
        InstallationContract contract = requireContract(contractId);

        contract.addEvent(InstallationContractEvent.builder()
                .actor(admin)
                .actorName(resolveDisplayName(admin))
                .actorRole(ContractActorRole.ADMIN)
                .eventType(ContractEventType.COMMENT)
                .fromStatus(contract.getStatus())
                .toStatus(contract.getStatus())
                .message(request.message().trim())
                .build());

        InstallationContract saved = contractRepository.save(contract);

        notifyCustomer(
                saved,
                "Phản hồi từ HESTA cho hợp đồng " + saved.getCode(),
                truncate(request.message(), 140),
                NotificationPriority.MEDIUM
        );

        return ContractDetailResponse.from(saved);
    }

    @Override
    @Transactional
    public ContractDetailResponse confirmDeposit(UUID adminId, UUID contractId, ConfirmDepositRequest request) {
        User admin = requireUser(adminId);
        InstallationContract contract = requireContract(contractId);

        if (contract.getStatus() != InstallationContractStatus.AWAITING_DEPOSIT
                && contract.getStatus() != InstallationContractStatus.DEPOSIT_SUBMITTED) {
            throw new AppException(ErrorCode.CONTRACT_INVALID_STATUS);
        }

        InstallationContractStatus prev = contract.getStatus();
        BigDecimal verifiedAmount = (request != null && request.verifiedAmount() != null)
                ? request.verifiedAmount().max(BigDecimal.ZERO).setScale(2, RoundingMode.HALF_UP)
                : contract.getDepositAmount();

        if (request != null && trimToNull(request.referenceCode()) != null) {
            contract.setDepositReferenceCode(trimToNull(request.referenceCode()));
        }
        contract.setDepositVerifiedAmount(verifiedAmount);
        contract.setDepositVerifiedAt(OffsetDateTime.now());
        contract.setDepositVerifiedBy(admin);
        contract.setStatus(InstallationContractStatus.DEPOSIT_CONFIRMED);

        String note = request != null ? trimToNull(request.note()) : null;
        String msg = String.format("Admin %s đã đối soát tài khoản ngân hàng và xác nhận nhận đặt cọc %s đ.",
                resolveDisplayName(admin), formatVnd(verifiedAmount));
        if (note != null) {
            msg += " Ghi chú: " + note;
        }

        contract.addEvent(InstallationContractEvent.builder()
                .actor(admin)
                .actorName(resolveDisplayName(admin))
                .actorRole(ContractActorRole.ADMIN)
                .eventType(ContractEventType.DEPOSIT_CONFIRMED)
                .fromStatus(prev)
                .toStatus(InstallationContractStatus.DEPOSIT_CONFIRMED)
                .message(msg)
                .build());

        InstallationContract saved = contractRepository.save(contract);

        notifyCustomer(
                saved,
                "Đã xác nhận tiền đặt cọc — " + saved.getCode(),
                String.format("HESTA đã nhận khoản đặt cọc %s đ và sẵn sàng triển khai lắp đặt theo lịch hẹn.",
                        formatVnd(verifiedAmount)),
                NotificationPriority.HIGH
        );

        return ContractDetailResponse.from(saved);
    }

    @Override
    @Transactional
    public ContractDetailResponse rejectDeposit(UUID adminId, UUID contractId, RejectDepositRequest request) {
        User admin = requireUser(adminId);
        InstallationContract contract = requireContract(contractId);

        if (contract.getStatus() != InstallationContractStatus.DEPOSIT_SUBMITTED) {
            throw new AppException(ErrorCode.CONTRACT_INVALID_STATUS);
        }

        InstallationContractStatus prev = contract.getStatus();
        contract.setStatus(InstallationContractStatus.AWAITING_DEPOSIT);

        contract.addEvent(InstallationContractEvent.builder()
                .actor(admin)
                .actorName(resolveDisplayName(admin))
                .actorRole(ContractActorRole.ADMIN)
                .eventType(ContractEventType.DEPOSIT_REJECTED)
                .fromStatus(prev)
                .toStatus(InstallationContractStatus.AWAITING_DEPOSIT)
                .message("Cần kiểm tra lại giao dịch đặt cọc: " + request.reason().trim())
                .build());

        InstallationContract saved = contractRepository.save(contract);

        notifyCustomer(
                saved,
                "Cần kiểm tra lại tiền đặt cọc — " + saved.getCode(),
                request.reason().trim(),
                NotificationPriority.HIGH
        );

        return ContractDetailResponse.from(saved);
    }

    @Override
    @Transactional
    public ContractDetailResponse startInstallation(UUID adminId, UUID contractId, StartInstallationRequest request) {
        User admin = requireUser(adminId);
        InstallationContract contract = requireContract(contractId);

        if (contract.getStatus() != InstallationContractStatus.DEPOSIT_CONFIRMED) {
            throw new AppException(ErrorCode.CONTRACT_INVALID_STATUS);
        }

        InstallationContractStatus prev = contract.getStatus();
        contract.setStatus(InstallationContractStatus.INSTALLING);
        if (contract.getAdminSignedAt() == null) {
            contract.setAdminSignedBy(admin);
            contract.setAdminSignedName(resolveDisplayName(admin));
            contract.setAdminSignedAt(OffsetDateTime.now());
        }

        String note = request != null ? trimToNull(request.note()) : null;
        String msg = "Đội ngũ kỹ thuật HESTA đã bắt đầu triển khai lắp đặt thiết bị tại công trình.";
        if (note != null) {
            msg += " " + note;
        }

        contract.addEvent(InstallationContractEvent.builder()
                .actor(admin)
                .actorName(resolveDisplayName(admin))
                .actorRole(ContractActorRole.ADMIN)
                .eventType(ContractEventType.INSTALLING)
                .fromStatus(prev)
                .toStatus(InstallationContractStatus.INSTALLING)
                .message(msg)
                .build());

        InstallationContract saved = contractRepository.save(contract);

        notifyCustomer(
                saved,
                "Đang tiến hành lắp đặt — " + saved.getCode(),
                msg,
                NotificationPriority.MEDIUM
        );

        return ContractDetailResponse.from(saved);
    }

    /**
     * Turnkey Direct Provisioning (Bypass QR):
     * Tạo mới Ngôi nhà + các Phòng (hoặc liên kết vào Ngôi nhà phần cứng Demo có sẵn),
     * cấp quyền OWNER cho khách hàng, add trực tiếp thiết bị vào từng phòng và vô hiệu hóa mã QR cũ.
     */
    @Override
    @Transactional
    public ContractDetailResponse handover(UUID adminId, UUID contractId, HandoverContractRequest request) {
        User admin = requireUser(adminId);
        InstallationContract contract = requireContract(contractId);

        if (contract.getStatus() != InstallationContractStatus.DEPOSIT_CONFIRMED
                && contract.getStatus() != InstallationContractStatus.INSTALLING) {
            throw new AppException(ErrorCode.CONTRACT_INVALID_STATUS);
        }

        // 1. Resolve Customer account (by user_id or fallback by customer_email)
        User customer = contract.getUser();
        if (customer == null) {
            customer = userRepository.findByEmail(contract.getCustomerEmail())
                    .orElseThrow(() -> new AppException(ErrorCode.CONTRACT_HANDOVER_FAILED));
            contract.setUser(customer);
        }

        // 2. Create new Home OR link existing Home (e.g., Live Hardware Demo Home)
        String mode = request.mode().trim().toUpperCase();
        Home targetHome;
        if ("LINK_EXISTING_HOME".equals(mode)) {
            if (request.existingHomeId() == null) {
                throw new AppException(ErrorCode.HOME_NOT_FOUND);
            }
            targetHome = homeRepository.findById(request.existingHomeId())
                    .orElseThrow(() -> new AppException(ErrorCode.HOME_NOT_FOUND));
            ensureOwnerMembership(targetHome, customer, admin);
        } else if ("CREATE_NEW_HOME".equals(mode)) {
            String resolvedHomeName = nonBlankOr(request.homeName(),
                    nonBlankOr(contract.getHomeName(), "Nhà của " + contract.getCustomerName()));
            String resolvedAddress = nonBlankOr(request.homeAddress(), contract.getInstallAddress());

            targetHome = Home.builder()
                    .name(resolvedHomeName)
                    .address(resolvedAddress)
                    .createdBy(customer)
                    .build();
            targetHome = homeRepository.save(targetHome);
            ensureOwnerMembership(targetHome, customer, admin);
        } else {
            throw new AppException(ErrorCode.CONTRACT_INVALID_REQUEST);
        }

        // 3. Provision rooms & direct-assign devices without requiring QR scan
        final Home homeRef = targetHome;
        OffsetDateTime now = OffsetDateTime.now();
        Map<String, Room> roomCache = new HashMap<>();
        for (Room existingRoom : roomRepository.findByHomeId(homeRef.getId())) {
            roomCache.put(existingRoom.getName().trim().toLowerCase(), existingRoom);
        }

        List<HandoverDeviceInput> deviceInputs = request.devices() != null ? request.devices() : List.of();
        int provisionedCount = 0;

        // If CREATE_NEW_HOME and admin didn't customize device list, auto-create devices from contract items
        if ("CREATE_NEW_HOME".equals(mode) && deviceInputs.isEmpty() && !contract.getItems().isEmpty()) {
            List<HandoverDeviceInput> autoInputs = new ArrayList<>();
            for (InstallationContractItem item : contract.getItems()) {
                int qty = item.getQuantity() != null && item.getQuantity() > 0 ? item.getQuantity() : 1;
                for (int i = 1; i <= qty; i++) {
                    String name = qty > 1 ? item.getDeviceName() + " #" + i : item.getDeviceName();
                    autoInputs.add(new HandoverDeviceInput(
                            item.getId(),
                            null,
                            item.getCatalog() != null ? item.getCatalog().getId() : null,
                            name,
                            nonBlankOr(item.getRoomLabel(), "Phòng khách"),
                            null,
                            null,
                            item.getWarrantyMonths()
                    ));
                }
            }
            deviceInputs = autoInputs;
        }

        for (HandoverDeviceInput devInput : deviceInputs) {
            String roomLabel = nonBlankOr(devInput.roomName(), "Phòng khách");
            Room room = roomCache.computeIfAbsent(roomLabel.toLowerCase(), k -> {
                Room createdRoom = Room.builder()
                        .home(homeRef)
                        .name(roomLabel)
                        .icon(inferRoomIcon(roomLabel))
                        .build();
                return roomRepository.save(createdRoom);
            });

            InstallationContractItem contractItem = devInput.contractItemId() != null
                    ? contract.getItems().stream()
                            .filter(i -> i.getId().equals(devInput.contractItemId()))
                            .findFirst()
                            .orElse(null)
                    : null;

            Device provisionedDevice;
            if (devInput.existingDeviceId() != null) {
                // Attach an UNCLAIMED inventory device directly to the customer's room & home (bypass QR)
                provisionedDevice = deviceRepository.findByIdWithNode(devInput.existingDeviceId())
                        .orElseThrow(() -> new AppException(ErrorCode.DEVICE_NOT_FOUND));
                if (provisionedDevice.getStatus() != DeviceStatus.UNCLAIMED) {
                    throw new AppException(ErrorCode.DEVICE_ALREADY_CLAIMED);
                }

                EdgeNode edgeNode = resolveOrAttachEdgeNode(
                        targetHome,
                        provisionedDevice.getNode(),
                        devInput.nodeCode(),
                        provisionedDevice.getSerialNumber()
                );
                provisionedDevice.setNode(edgeNode);
                provisionedDevice.setRoom(room);
                if (nonBlankOr(devInput.deviceName(), null) != null) {
                    provisionedDevice.setName(devInput.deviceName().trim());
                }
                if (nonBlankOr(devInput.localId(), null) != null) {
                    provisionedDevice.setLocalId(devInput.localId().trim());
                }
                provisionedDevice.setStatus(DeviceStatus.ONLINE);
                provisionedDevice.setLastSeen(now);
                String localId = nonBlankOr(provisionedDevice.getLocalId(), provisionedDevice.getId().toString());
                provisionedDevice.setMqttTopic(String.format("hesta/nodes/%s/devices/%s/state", edgeNode.getNodeCode(), localId));
                provisionedDevice = deviceRepository.save(provisionedDevice);

                // Revoke / invalidate any active QR registration tokens for this device for security
                List<DeviceRegistrationToken> activeTokens = tokenRepository.findActiveTokensByDeviceId(provisionedDevice.getId());
                for (DeviceRegistrationToken t : activeTokens) {
                    t.setRevokedAt(now);
                }
                if (!activeTokens.isEmpty()) {
                    tokenRepository.saveAll(activeTokens);
                }
            } else {
                // Create directly from DeviceCatalog without needing QR scan
                DeviceCatalog catalog = devInput.catalogId() != null
                        ? catalogRepository.findById(devInput.catalogId()).orElse(null)
                        : (contractItem != null ? contractItem.getCatalog() : null);

                String prefix = catalog != null && catalog.getSerialPrefix() != null
                        ? catalog.getSerialPrefix()
                        : "HESTA-";
                String serialNumber = generateUniqueSerial(prefix);
                String localId = nonBlankOr(devInput.localId(),
                        "dev-" + serialNumber.toLowerCase().replaceAll("[^a-z0-9_-]", ""));

                EdgeNode edgeNode = resolveOrAttachEdgeNode(targetHome, null, devInput.nodeCode(), serialNumber);

                String deviceType = catalog != null
                        ? catalog.getDeviceType()
                        : (contractItem != null && contractItem.getDeviceType() != null ? contractItem.getDeviceType() : "SWITCH");
                String deviceName = nonBlankOr(devInput.deviceName(),
                        catalog != null ? catalog.getName() : (contractItem != null ? contractItem.getDeviceName() : "Thiết bị HESTA"));

                Device newDevice = Device.builder()
                        .name(deviceName)
                        .deviceType(deviceType.toUpperCase())
                        .model(catalog != null ? catalog.getModelCode() : (contractItem != null ? contractItem.getModelCode() : null))
                        .serialNumber(serialNumber)
                        .localId(localId)
                        .node(edgeNode)
                        .room(room)
                        .status(DeviceStatus.ONLINE)
                        .lastSeen(now)
                        .mqttTopic(String.format("hesta/nodes/%s/devices/%s/state", edgeNode.getNodeCode(), localId))
                        .currentState(new HashMap<>(Map.of("power", "OFF")))
                        .icon(catalog != null ? catalog.getIcon() : null)
                        .imageUrl(catalog != null ? catalog.getImageUrl() : null)
                        .catalog(catalog)
                        .capabilities(catalog != null && catalog.getDefaultCapabilities() != null
                                ? catalog.getDefaultCapabilities()
                                : new DeviceCapabilities())
                        .build();
                provisionedDevice = deviceRepository.save(newDevice);
            }

            int warrantyMonths = devInput.warrantyMonths() != null
                    ? devInput.warrantyMonths()
                    : (contractItem != null && contractItem.getWarrantyMonths() != null ? contractItem.getWarrantyMonths() : 12);

            contract.addProvisionedDevice(InstallationContractDevice.builder()
                    .contractItem(contractItem)
                    .device(provisionedDevice)
                    .room(room)
                    .deviceName(provisionedDevice.getName())
                    .serialNumber(provisionedDevice.getSerialNumber())
                    .roomName(room.getName())
                    .warrantyMonths(warrantyMonths)
                    .warrantyExpiresAt(now.plusMonths(warrantyMonths))
                    .build());
            provisionedCount++;
        }

        // 4. Finalize contract status -> HANDED_OVER
        InstallationContractStatus prev = contract.getStatus();
        contract.setHome(targetHome);
        contract.setHandoverMode(mode);
        contract.setHandedOverAt(now);
        contract.setHandedOverBy(admin);
        contract.setHandoverNote(trimToNull(request.handoverNote()));
        contract.setStatus(InstallationContractStatus.HANDED_OVER);

        String handoverMsg = "LINK_EXISTING_HOME".equals(mode)
                ? String.format("HESTA đã hoàn tất bàn giao và cấp quyền Chủ nhà (OWNER) cho ngôi nhà \"%s\" (bổ sung trực tiếp %d thiết bị).",
                        targetHome.getName(), provisionedCount)
                : String.format("HESTA đã khởi tạo ngôi nhà \"%s\" và bàn giao trực tiếp %d thiết bị sẵn sàng điều khiển (không cần quét QR).",
                        targetHome.getName(), provisionedCount);
        if (contract.getHandoverNote() != null) {
            handoverMsg += " Ghi chú: " + contract.getHandoverNote();
        }

        contract.addEvent(InstallationContractEvent.builder()
                .actor(admin)
                .actorName(resolveDisplayName(admin))
                .actorRole(ContractActorRole.ADMIN)
                .eventType(ContractEventType.HANDED_OVER)
                .fromStatus(prev)
                .toStatus(InstallationContractStatus.HANDED_OVER)
                .message(handoverMsg)
                .metadata(Map.of(
                        "homeId", targetHome.getId().toString(),
                        "homeName", targetHome.getName(),
                        "handoverMode", mode,
                        "provisionedDevicesCount", provisionedCount
                ))
                .build());

        InstallationContract saved = contractRepository.save(contract);

        notifyCustomer(
                saved,
                "Bàn giao hệ thống hoàn tất — " + targetHome.getName(),
                "Ngôi nhà và toàn bộ thiết bị theo hợp đồng " + saved.getCode() + " đã sẵn sàng. Bấm để vào bảng điều khiển ngay!",
                NotificationPriority.HIGH
        );

        log.info("Handed over contract [{}] to user [{}] in home [{}] mode [{}] with {} devices",
                saved.getCode(), customer.getEmail(), targetHome.getId(), mode, provisionedCount);

        return ContractDetailResponse.from(saved);
    }

    @Override
    @Transactional
    public ContractDetailResponse cancelByAdmin(UUID adminId, UUID contractId, CancelContractRequest request) {
        User admin = requireUser(adminId);
        InstallationContract contract = requireContract(contractId);
        if (!InstallationContractStatus.CANCELLABLE.contains(contract.getStatus())) {
            throw new AppException(ErrorCode.CONTRACT_INVALID_STATUS);
        }

        InstallationContractStatus prev = contract.getStatus();
        String reason = request != null ? trimToNull(request.reason()) : null;
        contract.setStatus(InstallationContractStatus.CANCELLED);
        contract.setCancelReason(reason != null ? reason : "HESTA hủy yêu cầu");

        contract.addEvent(InstallationContractEvent.builder()
                .actor(admin)
                .actorName(resolveDisplayName(admin))
                .actorRole(ContractActorRole.ADMIN)
                .eventType(ContractEventType.CANCELLED)
                .fromStatus(prev)
                .toStatus(InstallationContractStatus.CANCELLED)
                .message("HESTA hủy hợp đồng: " + contract.getCancelReason())
                .build());

        InstallationContract saved = contractRepository.save(contract);
        notifyCustomer(saved, "Hợp đồng " + saved.getCode() + " đã bị hủy", contract.getCancelReason(), NotificationPriority.MEDIUM);
        return ContractDetailResponse.from(saved);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    private InstallationContract buildInitialContract(PublicInstallationRequest request, User owner) {
        String code = generateRequestCode();
        ContractTemplates.PackageSpec pkg = ContractTemplates.findPackage(request.packageCode());
        ContractTemplates.TemplateSpec tpl = ContractTemplates.findTemplateOrDefault(
                pkg != null ? pkg.templateCode() : "STANDARD_HOME");

        InstallationContract contract = new InstallationContract();
        contract.setCode(code);
        contract.setUser(owner);
        contract.setCustomerName(request.customerName().trim());
        contract.setCustomerPhone(request.customerPhone().trim());
        contract.setCustomerEmail(request.customerEmail().trim().toLowerCase());
        contract.setInstallAddress(request.installAddress().trim());
        contract.setHomeName(nonBlankOr(request.homeName(), "Nhà của " + request.customerName().trim()));
        contract.setHomeType(nonBlankOr(request.homeType(), "APARTMENT").toUpperCase());
        contract.setFloorCount(request.floorCount() != null && request.floorCount() > 0 ? request.floorCount() : 1);
        contract.setBedroomCount(request.bedroomCount() != null && request.bedroomCount() >= 0 ? request.bedroomCount() : 2);
        contract.setRoomCount(request.roomCount() != null && request.roomCount() > 0
                ? request.roomCount()
                : Math.max(1, contract.getBedroomCount() + 2));
        contract.setBuildingCondition(nonBlankOr(request.buildingCondition(), "OCCUPIED").toUpperCase());
        contract.setDesiredTimeline(nonBlankOr(request.desiredTimeline(), "WITHIN_1_MONTH"));
        contract.setPackageCode(pkg != null ? pkg.code() : trimToNull(request.packageCode()));
        contract.setCustomerNote(trimToNull(request.customerNote()));
        contract.setPreferredInstallDate(request.preferredInstallDate());

        contract.setStatus(InstallationContractStatus.SUBMITTED);
        contract.setContractVersion(1);
        contract.setTemplateCode(tpl.code());
        contract.setTermsTitle(tpl.termsTitle());
        contract.setTermsContent(tpl.termsContent());

        // Provider & Bank defaults
        contract.setProviderCompanyName(ContractTemplates.DEFAULT_PROVIDER_COMPANY);
        contract.setProviderTaxCode(ContractTemplates.DEFAULT_PROVIDER_TAX_CODE);
        contract.setProviderAddress(ContractTemplates.DEFAULT_PROVIDER_ADDRESS);
        contract.setProviderRepresentative(ContractTemplates.DEFAULT_PROVIDER_REPRESENTATIVE);
        contract.setProviderHotline(ContractTemplates.DEFAULT_PROVIDER_HOTLINE);
        contract.setProviderEmail(ContractTemplates.DEFAULT_PROVIDER_EMAIL);
        contract.setBankName(ContractTemplates.DEFAULT_BANK_NAME);
        contract.setBankAccountNumber(ContractTemplates.DEFAULT_BANK_ACCOUNT_NUMBER);
        contract.setBankAccountHolder(ContractTemplates.DEFAULT_BANK_ACCOUNT_HOLDER);
        contract.setDepositTransferNote("HESTA COC " + code);

        // Store structured customer consultation needs (NOT binding contract items yet!)
        List<Map<String, Object>> structuredNeeds = new ArrayList<>();
        if (request.requestedDevices() != null && !request.requestedDevices().isEmpty()) {
            for (RequestedDeviceNeed need : request.requestedDevices()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("category", nonBlankOr(need.category(), "OTHER"));
                m.put("label", nonBlankOr(need.label(), "Thiết bị thông minh"));
                m.put("locations", need.locations() != null ? need.locations() : List.of());
                m.put("estimatedQuantity", nonBlankOr(need.estimatedQuantity(), "Chưa rõ – Cần tư vấn"));
                m.put("purposes", need.purposes() != null ? need.purposes() : List.of());
                m.put("needsConsultation", need.needsConsultation());
                if (trimToNull(need.note()) != null) {
                    m.put("note", need.note().trim());
                }
                structuredNeeds.add(m);
            }
        }
        contract.setRequestedDevices(structuredNeeds);

        // Auto-generate checklist of items Admin/Engineer must verify during survey
        contract.setSurveyChecklist(buildDefaultSurveyChecklist(structuredNeeds, contract.getBuildingCondition()));

        Map<String, Boolean> initialFlags = new LinkedHashMap<>();
        initialFlags.put("quantityConfirmed", false);
        initialFlags.put("locationConfirmed", false);
        initialFlags.put("compatibilityConfirmed", false);
        initialFlags.put("quoteReady", false);
        contract.setSurveyFlags(initialFlags);

        // Keep official contract items EMPTY and financials 0 until survey & quotation are issued by Admin!
        contract.replaceItems(List.of());
        contract.setSubtotalAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        contract.setInstallationFee(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        contract.setDiscountAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        contract.setTotalAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        contract.setDepositPercent(tpl.defaultDepositPercent());
        contract.setDepositAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));
        contract.setRemainingAmount(BigDecimal.ZERO.setScale(2, RoundingMode.HALF_UP));

        return contract;
    }

    private List<Map<String, Object>> buildDefaultSurveyChecklist(
            List<Map<String, Object>> needs,
            String buildingCondition
    ) {
        List<Map<String, Object>> checklist = new ArrayList<>();
        Set<String> cats = new HashSet<>();
        for (Map<String, Object> n : needs) {
            Object c = n.get("category");
            if (c != null) cats.add(c.toString().toUpperCase());
        }

        if (cats.contains("CAMERA_AI")) {
            checklist.add(checklistRow("chk-cam", "Xác nhận vị trí lắp Camera AI chính xác, góc quét và nhu cầu nhận diện té ngã", "CAMERA_AI"));
        }
        if (cats.contains("SMART_SWITCH")) {
            checklist.add(checklistRow("chk-sw", "Kiểm tra dây nguội (N), đế âm và loại công tắc tương thích từng phòng", "SMART_SWITCH"));
        }
        if (cats.contains("SENSORS")) {
            checklist.add(checklistRow("chk-sen", "Xác nhận loại & số lượng cảm biến (chuyển động, nhiệt ẩm, khói, gas)", "SENSORS"));
        }
        if (cats.contains("GATE_MOTOR")) {
            checklist.add(checklistRow("chk-gate", "Khảo sát kết cấu cánh cổng, nguồn điện và khả năng thi công motor cổng", "GATE_MOTOR"));
        }
        if (cats.contains("LIGHTING_RGB")) {
            checklist.add(checklistRow("chk-rgb", "Xác nhận vị trí trần/hắt sáng và công suất đèn LED RGB", "LIGHTING_RGB"));
        }
        if (cats.contains("AC_AI_HUB")) {
            checklist.add(checklistRow("chk-ac", "Kiểm tra tương thích hồng ngoại điều hòa & vị trí đặt trung tâm AI/giọng nói", "AC_AI_HUB"));
        }
        if ("UNDER_CONSTRUCTION".equalsIgnoreCase(buildingCondition) || "FINISHING".equalsIgnoreCase(buildingCondition)) {
            checklist.add(checklistRow("chk-wire", "Phối hợp bản vẽ đi dây nguồn & mạng LAN/Wi-Fi với đội điện nước công trình", "INFRA"));
        } else {
            checklist.add(checklistRow("chk-wifi", "Kiểm tra vùng phủ sóng Wi-Fi và điều kiện lắp đặt không đục phá", "INFRA"));
        }
        if (checklist.isEmpty()) {
            checklist.add(checklistRow("chk-general", "Liên hệ khách hàng tư vấn nhu cầu chi tiết và khảo sát mặt bằng thực tế", "GENERAL"));
        }
        return checklist;
    }

    private static Map<String, Object> checklistRow(String id, String label, String category) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", id);
        m.put("label", label);
        m.put("category", category);
        m.put("confirmed", false);
        return m;
    }

    private void ensureOwnerMembership(Home home, User customer, User invitedBy) {
        Optional<HomeMember> existing = homeMemberRepository.findByHomeIdAndUserId(home.getId(), customer.getId());
        if (existing.isPresent()) {
            HomeMember member = existing.get();
            member.setRole(HomeRole.OWNER);
            member.setStatus(MemberStatus.ACTIVE);
            member.setAccessScope(AccessScope.ALL);
            member.setAllowRemoteControl(true);
            member.setAllowSceneCreation(true);
            member.setAllowVoiceOverride(true);
            homeMemberRepository.save(member);
        } else {
            HomeMember member = HomeMember.builder()
                    .home(home)
                    .user(customer)
                    .role(HomeRole.OWNER)
                    .status(MemberStatus.ACTIVE)
                    .accessScope(AccessScope.ALL)
                    .allowRemoteControl(true)
                    .allowSceneCreation(true)
                    .allowVoiceOverride(true)
                    .invitedBy(invitedBy)
                    .build();
            homeMemberRepository.save(member);
        }
    }

    private EdgeNode resolveOrAttachEdgeNode(Home home, EdgeNode existingNode, String requestedNodeCode, String serial) {
        if (requestedNodeCode != null && !requestedNodeCode.isBlank()) {
            String code = requestedNodeCode.trim();
            EdgeNode node = edgeNodeRepository.findByHomeIdAndNodeCode(home.getId(), code)
                    .orElseGet(() -> edgeNodeRepository.findByNodeCode(code)
                            .orElseGet(() -> EdgeNode.builder()
                                    .nodeCode(code)
                                    .status(EdgeNodeStatus.ONLINE)
                                    .build()));
            node.setHome(home);
            node.setStatus(EdgeNodeStatus.ONLINE);
            return edgeNodeRepository.save(node);
        }
        if (existingNode != null) {
            existingNode.setHome(home);
            existingNode.setStatus(EdgeNodeStatus.ONLINE);
            return edgeNodeRepository.save(existingNode);
        }
        String autoCode = "node-" + serial.toLowerCase().replaceAll("[^a-z0-9_-]", "");
        EdgeNode node = edgeNodeRepository.findByHomeIdAndNodeCode(home.getId(), autoCode)
                .orElseGet(() -> edgeNodeRepository.findByNodeCode(autoCode)
                        .orElseGet(() -> EdgeNode.builder()
                                .nodeCode(autoCode)
                                .status(EdgeNodeStatus.ONLINE)
                                .build()));
        node.setHome(home);
        node.setStatus(EdgeNodeStatus.ONLINE);
        return edgeNodeRepository.save(node);
    }

    private String generateUniqueSerial(String prefix) {
        String cleanPrefix = prefix.endsWith("-") ? prefix : prefix + "-";
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidate = cleanPrefix + String.format("%06d", SECURE_RANDOM.nextInt(1_000_000));
            if (!deviceRepository.existsBySerialNumber(candidate)) {
                return candidate;
            }
        }
        return cleanPrefix + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private String generateRequestCode() {
        String year = OffsetDateTime.now(BUSINESS_ZONE).format(CODE_YEAR);
        for (int attempt = 0; attempt < 20; attempt++) {
            String candidate = String.format("YC-%s-%04d", year, 1000 + SECURE_RANDOM.nextInt(9000));
            if (!contractRepository.existsByCode(candidate)) {
                return candidate;
            }
        }
        return "YC-" + year + "-" + UUID.randomUUID().toString().substring(0, 6).toUpperCase();
    }

    private static String generateHexToken(int bytes) {
        byte[] buf = new byte[bytes];
        SECURE_RANDOM.nextBytes(buf);
        return HexFormat.of().formatHex(buf);
    }

    private static BankInfoSnapshot defaultProviderSnapshot() {
        return new BankInfoSnapshot(
                ContractTemplates.DEFAULT_PROVIDER_COMPANY,
                ContractTemplates.DEFAULT_PROVIDER_TAX_CODE,
                ContractTemplates.DEFAULT_PROVIDER_ADDRESS,
                ContractTemplates.DEFAULT_PROVIDER_REPRESENTATIVE,
                ContractTemplates.DEFAULT_PROVIDER_HOTLINE,
                ContractTemplates.DEFAULT_PROVIDER_EMAIL,
                ContractTemplates.DEFAULT_BANK_NAME,
                ContractTemplates.DEFAULT_BANK_ACCOUNT_NUMBER,
                ContractTemplates.DEFAULT_BANK_ACCOUNT_HOLDER
        );
    }

    private User requireUser(UUID userId) {
        return userRepository.findById(userId)
                .orElseThrow(() -> new AppException(ErrorCode.USER_NOT_FOUND));
    }

    private InstallationContract requireContract(UUID contractId) {
        return contractRepository.findById(contractId)
                .orElseThrow(() -> new AppException(ErrorCode.CONTRACT_NOT_FOUND));
    }

    private InstallationContract requireCustomerContract(User user, UUID contractId) {
        InstallationContract contract = requireContract(contractId);
        if (contract.getUser() != null && contract.getUser().getId().equals(user.getId())) {
            return contract;
        }
        if (contract.getUser() == null && contract.getCustomerEmail().equalsIgnoreCase(user.getEmail())) {
            contract.setUser(user);
            return contractRepository.save(contract);
        }
        throw new AppException(ErrorCode.UNAUTHORIZED);
    }

    private void notifyAdmins(String title, String message, NotificationPriority priority) {
        Set<UUID> adminIds = userRepository.findAllByPlatformRole(PlatformRole.ADMIN)
                .stream()
                .map(User::getId)
                .collect(Collectors.toSet());
        if (!adminIds.isEmpty()) {
            eventPublisher.publishEvent(new ContractNotificationEvent(null, adminIds, title, message, priority));
        }
    }

    private void notifyCustomer(InstallationContract contract, String title, String message, NotificationPriority priority) {
        UUID customerId = contract.getUser() != null ? contract.getUser().getId() : null;
        if (customerId == null) {
            customerId = userRepository.findByEmail(contract.getCustomerEmail())
                    .map(User::getId)
                    .orElse(null);
        }
        if (customerId != null) {
            UUID homeId = contract.getHome() != null ? contract.getHome().getId() : null;
            eventPublisher.publishEvent(new ContractNotificationEvent(homeId, Set.of(customerId), title, message, priority));
        }
    }

    private static String buildSubmissionMessage(InstallationContract c) {
        StringBuilder sb = new StringBuilder("Đã gửi yêu cầu khảo sát & lắp đặt cho ")
                .append(c.getHomeName())
                .append(" tại ")
                .append(c.getInstallAddress());
        if (c.getPackageCode() != null) {
            sb.append(" (Gói: ").append(c.getPackageCode()).append(")");
        }
        if (c.getCustomerNote() != null) {
            sb.append(". Ghi chú: ").append(c.getCustomerNote());
        }
        return sb.toString();
    }

    private static DeviceCatalog findCatalogForBom(List<DeviceCatalog> catalogs, ContractTemplates.PackageBomItem bom) {
        if (bom.modelCode() != null && !bom.modelCode().isBlank()) {
            Optional<DeviceCatalog> byModel = catalogs.stream()
                    .filter(c -> c.getModelCode() != null && c.getModelCode().equalsIgnoreCase(bom.modelCode()))
                    .findFirst();
            if (byModel.isPresent()) return byModel.get();
        }
        if (bom.deviceType() != null && !bom.deviceType().isBlank()) {
            return catalogs.stream()
                    .filter(c -> c.getDeviceType() != null && c.getDeviceType().equalsIgnoreCase(bom.deviceType()))
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    private static String defaultRoomForType(String deviceType) {
        if (deviceType == null) return "Phòng khách";
        return switch (deviceType.toUpperCase()) {
            case "GAS_SENSOR", "SMOKE_SENSOR", "FLAME_SENSOR" -> "Nhà bếp";
            case "AIR_CONDITIONER", "AC", "CURTAIN" -> "Phòng ngủ";
            case "GATE", "DOOR", "SMART_LOCK", "LOCK", "ROLLING_DOOR" -> "Cửa chính";
            default -> "Phòng khách";
        };
    }

    private static String inferRoomIcon(String roomName) {
        String lower = roomName.toLowerCase();
        if (lower.contains("ngủ") || lower.contains("bed")) return "bed";
        if (lower.contains("bếp") || lower.contains("kitchen")) return "utensils";
        if (lower.contains("tắm") || lower.contains("wc") || lower.contains("bath")) return "bath";
        if (lower.contains("cửa") || lower.contains("sân") || lower.contains("cổng")) return "door-open";
        return "sofa";
    }

    private static String resolveDisplayName(User user) {
        if (user == null) return "Hệ thống";
        if (user.getFullName() != null && !user.getFullName().isBlank()) return user.getFullName().trim();
        return user.getEmail();
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private static String nonBlankOr(String candidate, String fallback) {
        String cleaned = trimToNull(candidate);
        return cleaned != null ? cleaned : fallback;
    }

    private static String truncate(String text, int max) {
        if (text == null) return "";
        String t = text.trim();
        return t.length() <= max ? t : t.substring(0, max - 1) + "…";
    }

    private static String formatVnd(BigDecimal amount) {
        if (amount == null) return "0";
        return String.format("%,.0f", amount);
    }
}
