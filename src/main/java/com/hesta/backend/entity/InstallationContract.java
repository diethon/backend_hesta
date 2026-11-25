package com.hesta.backend.entity;

import com.hesta.backend.enums.InstallationContractStatus;
import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.annotations.UpdateTimestamp;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "installation_contracts")
@Getter
@Setter
@NoArgsConstructor
public class InstallationContract {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "code", nullable = false, unique = true, length = 30)
    private String code;

    @Column(name = "claim_token", unique = true, length = 64)
    private String claimToken;

    @Column(name = "claim_token_expires_at")
    private OffsetDateTime claimTokenExpiresAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "home_id")
    private Home home;

    @Column(name = "customer_name", nullable = false, length = 150)
    private String customerName;

    @Column(name = "customer_phone", nullable = false, length = 30)
    private String customerPhone;

    @Column(name = "customer_email", nullable = false, length = 150)
    private String customerEmail;

    @Column(name = "install_address", nullable = false, columnDefinition = "TEXT")
    private String installAddress;

    @Column(name = "home_name", length = 150)
    private String homeName;

    @Column(name = "home_type", nullable = false, length = 30)
    private String homeType = "APARTMENT";

    @Column(name = "room_count")
    private Integer roomCount = 3;

    @Column(name = "floor_count")
    private Integer floorCount = 1;

    @Column(name = "bedroom_count")
    private Integer bedroomCount = 2;

    @Column(name = "building_condition", nullable = false, length = 40)
    private String buildingCondition = "OCCUPIED";

    @Column(name = "desired_timeline", length = 60)
    private String desiredTimeline;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "requested_devices", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> requestedDevices = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "survey_checklist", nullable = false, columnDefinition = "jsonb")
    private List<Map<String, Object>> surveyChecklist = new ArrayList<>();

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "survey_flags", nullable = false, columnDefinition = "jsonb")
    private Map<String, Boolean> surveyFlags = new HashMap<>();

    @Column(name = "survey_notes", columnDefinition = "TEXT")
    private String surveyNotes;

    @Column(name = "survey_completed_at")
    private OffsetDateTime surveyCompletedAt;

    @Column(name = "package_code", length = 50)
    private String packageCode;

    @Column(name = "customer_note", columnDefinition = "TEXT")
    private String customerNote;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private InstallationContractStatus status = InstallationContractStatus.SUBMITTED;

    @Column(name = "contract_version", nullable = false)
    private Integer contractVersion = 1;

    @Column(name = "template_code", nullable = false, length = 50)
    private String templateCode = "STANDARD_HOME";

    @Column(name = "subtotal_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal subtotalAmount = BigDecimal.ZERO;

    @Column(name = "installation_fee", nullable = false, precision = 14, scale = 2)
    private BigDecimal installationFee = BigDecimal.ZERO;

    @Column(name = "discount_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal discountAmount = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(name = "deposit_percent", nullable = false)
    private Integer depositPercent = 30;

    @Column(name = "deposit_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal depositAmount = BigDecimal.ZERO;

    @Column(name = "remaining_amount", nullable = false, precision = 14, scale = 2)
    private BigDecimal remainingAmount = BigDecimal.ZERO;

    @Column(name = "provider_company_name", nullable = false, length = 200)
    private String providerCompanyName;

    @Column(name = "provider_tax_code", nullable = false, length = 50)
    private String providerTaxCode;

    @Column(name = "provider_address", nullable = false, columnDefinition = "TEXT")
    private String providerAddress;

    @Column(name = "provider_representative", nullable = false, length = 150)
    private String providerRepresentative;

    @Column(name = "provider_hotline", nullable = false, length = 30)
    private String providerHotline;

    @Column(name = "provider_email", nullable = false, length = 150)
    private String providerEmail;

    @Column(name = "bank_name", nullable = false, length = 200)
    private String bankName;

    @Column(name = "bank_account_number", nullable = false, length = 50)
    private String bankAccountNumber;

    @Column(name = "bank_account_holder", nullable = false, length = 150)
    private String bankAccountHolder;

    @Column(name = "deposit_transfer_note", length = 150)
    private String depositTransferNote;

    @Column(name = "deposit_proof_url", columnDefinition = "TEXT")
    private String depositProofUrl;

    @Column(name = "deposit_reference_code", length = 100)
    private String depositReferenceCode;

    @Column(name = "deposit_note", columnDefinition = "TEXT")
    private String depositNote;

    @Column(name = "deposit_submitted_at")
    private OffsetDateTime depositSubmittedAt;

    @Column(name = "deposit_verified_at")
    private OffsetDateTime depositVerifiedAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "deposit_verified_by")
    private User depositVerifiedBy;

    @Column(name = "deposit_verified_amount", precision = 14, scale = 2)
    private BigDecimal depositVerifiedAmount;

    @Column(name = "preferred_install_date")
    private OffsetDateTime preferredInstallDate;

    @Column(name = "scheduled_install_start")
    private OffsetDateTime scheduledInstallStart;

    @Column(name = "scheduled_install_end")
    private OffsetDateTime scheduledInstallEnd;

    @Column(name = "terms_title", nullable = false, length = 255)
    private String termsTitle;

    @Column(name = "terms_content", columnDefinition = "TEXT")
    private String termsContent;

    @Column(name = "special_terms", columnDefinition = "TEXT")
    private String specialTerms;

    @Column(name = "admin_note", columnDefinition = "TEXT")
    private String adminNote;

    @Column(name = "cancel_reason", columnDefinition = "TEXT")
    private String cancelReason;

    @Column(name = "user_signature_name", length = 150)
    private String userSignatureName;

    @Column(name = "user_signature_data", columnDefinition = "TEXT")
    private String userSignatureData;

    @Column(name = "user_signed_at")
    private OffsetDateTime userSignedAt;

    @Column(name = "user_signed_ip", length = 64)
    private String userSignedIp;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "admin_signed_by")
    private User adminSignedBy;

    @Column(name = "admin_signed_name", length = 150)
    private String adminSignedName;

    @Column(name = "admin_signed_at")
    private OffsetDateTime adminSignedAt;

    @Column(name = "handover_mode", length = 30)
    private String handoverMode;

    @Column(name = "handed_over_at")
    private OffsetDateTime handedOverAt;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "handed_over_by")
    private User handedOverBy;

    @Column(name = "handover_note", columnDefinition = "TEXT")
    private String handoverNote;

    @Version
    @Column(name = "version", nullable = false)
    private Long version = 0L;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @OneToMany(mappedBy = "contract", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("sortOrder ASC")
    private List<InstallationContractItem> items = new ArrayList<>();

    @OneToMany(mappedBy = "contract", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("provisionedAt ASC")
    private List<InstallationContractDevice> provisionedDevices = new ArrayList<>();

    @OneToMany(mappedBy = "contract", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("createdAt ASC")
    private List<InstallationContractEvent> events = new ArrayList<>();

    public void replaceItems(List<InstallationContractItem> newItems) {
        items.clear();
        for (int i = 0; i < newItems.size(); i++) {
            InstallationContractItem item = newItems.get(i);
            item.setContract(this);
            item.setSortOrder(i);
            items.add(item);
        }
    }

    public void addProvisionedDevice(InstallationContractDevice device) {
        device.setContract(this);
        provisionedDevices.add(device);
    }

    public void addEvent(InstallationContractEvent event) {
        event.setContract(this);
        events.add(event);
    }
}
