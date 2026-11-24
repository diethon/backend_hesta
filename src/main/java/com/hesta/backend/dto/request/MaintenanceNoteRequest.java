package com.hesta.backend.dto.request;

import jakarta.validation.constraints.Size;

/** Ghi chú/lý do tuỳ chọn cho các thao tác huỷ, từ chối, xác nhận. */
public record MaintenanceNoteRequest(
        @Size(max = 1000, message = "MAINTENANCE_INVALID_REQUEST") String note
) {
}
