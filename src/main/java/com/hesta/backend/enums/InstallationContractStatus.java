package com.hesta.backend.enums;

import java.util.EnumSet;
import java.util.Set;

/**
 * Vòng đời hợp đồng cung cấp & lắp đặt thiết bị HESTA (installation_contracts.status).
 */
public enum InstallationContractStatus {
    SUBMITTED,
    SURVEYING,
    QUOTED,
    REVISION_REQUESTED,
    AWAITING_DEPOSIT,
    DEPOSIT_SUBMITTED,
    DEPOSIT_CONFIRMED,
    INSTALLING,
    HANDED_OVER,
    CANCELLED;

    /** Hợp đồng đã được khách ký: khóa cứng thiết bị, giá trị và điều khoản. */
    public static final Set<InstallationContractStatus> LOCKED_TERMS = EnumSet.of(
            AWAITING_DEPOSIT, DEPOSIT_SUBMITTED, DEPOSIT_CONFIRMED, INSTALLING, HANDED_OVER);

    /** Các trạng thái Admin được phép khảo sát và soạn/cập nhật báo giá & điều khoản. */
    public static final Set<InstallationContractStatus> EDITABLE_PROPOSAL = EnumSet.of(
            SUBMITTED, SURVEYING, QUOTED, REVISION_REQUESTED);

    /** Các trạng thái được phép hủy. */
    public static final Set<InstallationContractStatus> CANCELLABLE = EnumSet.of(
            SUBMITTED, SURVEYING, QUOTED, REVISION_REQUESTED, AWAITING_DEPOSIT);
}
