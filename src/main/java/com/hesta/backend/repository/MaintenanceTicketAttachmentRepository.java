package com.hesta.backend.repository;

import com.hesta.backend.entity.MaintenanceTicketAttachment;
import org.springframework.data.jpa.repository.JpaRepository;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface MaintenanceTicketAttachmentRepository extends JpaRepository<MaintenanceTicketAttachment, UUID> {

    List<MaintenanceTicketAttachment> findAllByIdIn(Collection<UUID> ids);

    List<MaintenanceTicketAttachment> findByTicketIsNullAndCreatedAtBefore(OffsetDateTime cutoff);
}
