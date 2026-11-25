package com.hesta.backend.service;

import com.hesta.backend.dto.contract.ContractRequests.*;
import com.hesta.backend.dto.contract.ContractResponses.*;
import com.hesta.backend.entity.*;
import com.hesta.backend.enums.*;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.*;
import com.hesta.backend.service.impl.InstallationContractServiceImpl;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InstallationContractServiceTest {

    @Mock private InstallationContractRepository contractRepository;
    @Mock private DeviceCatalogRepository catalogRepository;
    @Mock private DeviceRepository deviceRepository;
    @Mock private DeviceRegistrationTokenRepository tokenRepository;
    @Mock private EdgeNodeRepository edgeNodeRepository;
    @Mock private HomeRepository homeRepository;
    @Mock private HomeMemberRepository homeMemberRepository;
    @Mock private RoomRepository roomRepository;
    @Mock private UserRepository userRepository;
    @Mock private CloudinaryService cloudinaryService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks
    private InstallationContractServiceImpl service;

    private User admin;
    private User customer;
    private DeviceCatalog switchCatalog;
    private InstallationContract contract;

    @BeforeEach
    void setUp() {
        admin = new User();
        admin.setId(UUID.randomUUID());
        admin.setEmail("admin@hesta.homes");
        admin.setFullName("Quản trị HESTA");
        admin.setPlatformRole(PlatformRole.ADMIN);

        customer = new User();
        customer.setId(UUID.randomUUID());
        customer.setEmail("khachhang@example.com");
        customer.setFullName("Nguyễn Văn An");
        customer.setPlatformRole(PlatformRole.USER);

        switchCatalog = DeviceCatalog.builder()
                .id(UUID.randomUUID())
                .name("Công tắc thông minh 3 nút")
                .deviceType("SWITCH")
                .modelCode("HESTA-SW-03")
                .serialPrefix("HSW-")
                .unitPrice(new BigDecimal("490000"))
                .warrantyMonths(12)
                .isActive(true)
                .build();

        contract = new InstallationContract();
        contract.setId(UUID.randomUUID());
        contract.setCode("HD-2026-1001");
        contract.setUser(customer);
        contract.setCustomerName("Nguyễn Văn An");
        contract.setCustomerPhone("0901234567");
        contract.setCustomerEmail("khachhang@example.com");
        contract.setInstallAddress("123 Nguyễn Huệ, Quận 1, TP.HCM");
        contract.setHomeName("Căn hộ Vinhomes");
        contract.setStatus(InstallationContractStatus.SUBMITTED);
        contract.setProviderCompanyName("CÔNG TY TNHH CÔNG NGHỆ NHÀ THÔNG MINH HESTA");
        contract.setProviderTaxCode("0318202688");
        contract.setProviderAddress("TP. Thủ Đức");
        contract.setProviderRepresentative("HESTA");
        contract.setProviderHotline("1900 2026");
        contract.setProviderEmail("contracts@hesta.homes");
        contract.setBankName("Vietcombank");
        contract.setBankAccountNumber("102688882026");
        contract.setBankAccountHolder("CONG TY TNHH CONG NGHE HESTA");
        contract.setTermsTitle("HỢP ĐỒNG HESTA");
    }

    @Test
    void fullLifecycle_quote_sign_lock_deposit_and_turnkeyHandoverBypassesQr() {
        when(userRepository.findById(admin.getId())).thenReturn(Optional.of(admin));
        when(userRepository.findById(customer.getId())).thenReturn(Optional.of(customer));
        when(userRepository.findAllByPlatformRole(PlatformRole.ADMIN)).thenReturn(List.of(admin));
        when(contractRepository.findById(contract.getId())).thenReturn(Optional.of(contract));
        when(contractRepository.save(any(InstallationContract.class))).thenAnswer(inv -> inv.getArgument(0));
        when(catalogRepository.findById(switchCatalog.getId())).thenReturn(Optional.of(switchCatalog));

        // 0. Admin conducts technical survey -> SURVEYING
        AdminSurveyUpdateRequest surveyReq = new AdminSurveyUpdateRequest(
                3, 3, "FINISHING", "WITHIN_1_MONTH",
                List.of(Map.of("category", "SMART_SWITCH", "label", "Công tắc thông minh", "estimatedQuantity", "6")),
                List.of(Map.of("id", "chk-sw", "label", "Kiểm tra dây nguội", "category", "SMART_SWITCH", "confirmed", true)),
                Map.of("quantityConfirmed", true, "locationConfirmed", true, "compatibilityConfirmed", true, "quoteReady", false),
                "Đã khảo sát thực tế: đủ dây nguội",
                true
        );
        ContractDetailResponse surveyed = service.updateSurvey(admin.getId(), contract.getId(), surveyReq);
        assertEquals(InstallationContractStatus.SURVEYING, surveyed.status());
        assertNotNull(surveyed.surveyCompletedAt());

        // 1. Admin sends proposal v1 (2 switches * 490,000 + 500,000 install - 80,000 discount = 1,400,000; 30% deposit = 420,000)
        AdminContractProposalRequest proposalReq = new AdminContractProposalRequest(
                "STANDARD_HOME", null, null, "Ưu đãi tặng khảo sát",
                new BigDecimal("500000"), new BigDecimal("80000"), 30,
                OffsetDateTime.now().plusDays(2), OffsetDateTime.now().plusDays(2).plusHours(3),
                "Gửi anh bản báo giá v1",
                null, null, null, null, null, null, null, null, null,
                List.of(new ProposalItemInput(
                        switchCatalog.getId(), "Công tắc 3 nút", "HESTA-SW-03", "SWITCH",
                        "Phòng khách", 2, new BigDecimal("490000"), 12))
        );
        ContractDetailResponse quoted = service.saveProposal(admin.getId(), contract.getId(), proposalReq);
        assertEquals(InstallationContractStatus.QUOTED, quoted.status());
        assertEquals(new BigDecimal("1400000.00"), quoted.totalAmount());
        assertEquals(new BigDecimal("420000.00"), quoted.depositAmount());
        assertEquals(new BigDecimal("980000.00"), quoted.remainingAmount());
        assertEquals("HESTA COC HD-2026-1001", quoted.depositTransferNote());

        // 2. Customer signs -> AWAITING_DEPOSIT
        ContractDetailResponse signed = service.signContract(
                customer.getId(), contract.getId(), "127.0.0.1",
                new SignContractRequest("Nguyễn Văn An", "data:image/png;base64,AAA", true));
        assertEquals(InstallationContractStatus.AWAITING_DEPOSIT, signed.status());
        assertNotNull(signed.userSignedAt());

        // 3. Trying to edit proposal after signing must fail with CONTRACT_LOCKED_AFTER_SIGN
        AppException lockedEx = assertThrows(AppException.class,
                () -> service.saveProposal(admin.getId(), contract.getId(), proposalReq));
        assertEquals(ErrorCode.CONTRACT_LOCKED_AFTER_SIGN, lockedEx.getErrorCode());

        // 4. Customer submits deposit proof -> DEPOSIT_SUBMITTED
        ContractDetailResponse proofRes = service.submitDepositProof(
                customer.getId(), contract.getId(),
                new SubmitDepositProofRequest("FT2633012345", "https://cdn.example/receipt.png", "Đã chuyển VCB"));
        assertEquals(InstallationContractStatus.DEPOSIT_SUBMITTED, proofRes.status());

        // 5. Admin confirms deposit -> DEPOSIT_CONFIRMED
        ContractDetailResponse confirmed = service.confirmDeposit(
                admin.getId(), contract.getId(),
                new ConfirmDepositRequest(new BigDecimal("420000"), "FT2633012345", "Đã nhận đủ trên VCB"));
        assertEquals(InstallationContractStatus.DEPOSIT_CONFIRMED, confirmed.status());

        // 6. Admin starts installation -> INSTALLING
        ContractDetailResponse installing = service.startInstallation(
                admin.getId(), contract.getId(), new StartInstallationRequest("Kỹ thuật viên đang tới"));
        assertEquals(InstallationContractStatus.INSTALLING, installing.status());

        // 7. Admin executes Turnkey Handover (CREATE_NEW_HOME) with 1 UNCLAIMED inventory device (must revoke QR token)
        Device unclaimedDev = Device.builder()
                .id(UUID.randomUUID())
                .name("Công tắc kho")
                .deviceType("SWITCH")
                .serialNumber("HSW-0001")
                .localId("dev-hsw-0001")
                .status(DeviceStatus.UNCLAIMED)
                .catalog(switchCatalog)
                .currentState(new HashMap<>(Map.of("power", "OFF")))
                .build();
        DeviceRegistrationToken qrToken = DeviceRegistrationToken.builder()
                .id(UUID.randomUUID())
                .device(unclaimedDev)
                .tokenHash("hash-123")
                .expiresAt(OffsetDateTime.now().plusDays(7))
                .build();

        when(homeRepository.save(any(Home.class))).thenAnswer(inv -> {
            Home h = inv.getArgument(0);
            h.setId(UUID.randomUUID());
            return h;
        });
        when(homeMemberRepository.findByHomeIdAndUserId(any(), any())).thenReturn(Optional.empty());
        when(homeMemberRepository.save(any(HomeMember.class))).thenAnswer(inv -> inv.getArgument(0));
        when(roomRepository.findByHomeId(any())).thenReturn(List.of());
        when(roomRepository.save(any(Room.class))).thenAnswer(inv -> {
            Room r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            return r;
        });
        when(deviceRepository.findByIdWithNode(unclaimedDev.getId())).thenReturn(Optional.of(unclaimedDev));
        when(edgeNodeRepository.findByHomeIdAndNodeCode(any(), any())).thenReturn(Optional.empty());
        when(edgeNodeRepository.findByNodeCode(any())).thenReturn(Optional.empty());
        when(edgeNodeRepository.save(any(EdgeNode.class))).thenAnswer(inv -> {
            EdgeNode n = inv.getArgument(0);
            n.setId(UUID.randomUUID());
            return n;
        });
        when(deviceRepository.save(any(Device.class))).thenAnswer(inv -> inv.getArgument(0));
        when(tokenRepository.findActiveTokensByDeviceId(unclaimedDev.getId())).thenReturn(List.of(qrToken));

        HandoverContractRequest handoverReq = new HandoverContractRequest(
                "CREATE_NEW_HOME",
                null,
                "Căn hộ Vinhomes",
                "123 Nguyễn Huệ",
                "Bàn giao hoàn chỉnh",
                List.of(new HandoverDeviceInput(
                        null, unclaimedDev.getId(), switchCatalog.getId(),
                        "Công tắc phòng khách", "Phòng khách", "esp32-living-01", "sw1", 12))
        );

        ContractDetailResponse handedOver = service.handover(admin.getId(), contract.getId(), handoverReq);
        assertEquals(InstallationContractStatus.HANDED_OVER, handedOver.status());
        assertNotNull(handedOver.homeId());
        assertEquals(1, handedOver.provisionedDevices().size());
        assertEquals(DeviceStatus.ONLINE, unclaimedDev.getStatus());
        assertEquals("hesta/nodes/esp32-living-01/devices/sw1/state", unclaimedDev.getMqttTopic());
        assertNotNull(qrToken.getRevokedAt(), "Old QR token must be revoked on direct handover");

        ArgumentCaptor<HomeMember> memberCaptor = ArgumentCaptor.forClass(HomeMember.class);
        verify(homeMemberRepository).save(memberCaptor.capture());
        assertEquals(HomeRole.OWNER, memberCaptor.getValue().getRole());
        assertEquals(customer.getId(), memberCaptor.getValue().getUser().getId());
    }
}
