package com.hesta.backend.repository;

import com.hesta.backend.entity.MaintenanceTicket;
import com.hesta.backend.enums.MaintenanceTicketStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

public interface MaintenanceTicketRepository extends JpaRepository<MaintenanceTicket, UUID> {

    Optional<MaintenanceTicket> findByIdAndHomeId(UUID id, UUID homeId);

    /** Owner / ALL-scope view: every ticket of the home. */
    @Query("""
        SELECT t FROM MaintenanceTicket t
        WHERE t.home.id = :homeId
          AND (:status IS NULL OR t.status = :status)
          AND (:deviceId IS NULL OR EXISTS (
                SELECT 1 FROM MaintenanceTicketDevice d WHERE d.ticket = t AND d.device.id = :deviceId))
    """)
    Page<MaintenanceTicket> findForHome(
            @Param("homeId") UUID homeId,
            @Param("status") MaintenanceTicketStatus status,
            @Param("deviceId") UUID deviceId,
            Pageable pageable);

    /** CUSTOM-scope member view: tickets they created or that involve a device they can see. */
    @Query("""
        SELECT t FROM MaintenanceTicket t
        WHERE t.home.id = :homeId
          AND (:status IS NULL OR t.status = :status)
          AND (:deviceId IS NULL OR EXISTS (
                SELECT 1 FROM MaintenanceTicketDevice d WHERE d.ticket = t AND d.device.id = :deviceId))
          AND (t.createdBy.id = :userId OR EXISTS (
                SELECT 1 FROM MaintenanceTicketDevice v WHERE v.ticket = t AND v.device.id IN :visibleDeviceIds))
    """)
    Page<MaintenanceTicket> findForMember(
            @Param("homeId") UUID homeId,
            @Param("userId") UUID userId,
            @Param("visibleDeviceIds") Collection<UUID> visibleDeviceIds,
            @Param("status") MaintenanceTicketStatus status,
            @Param("deviceId") UUID deviceId,
            Pageable pageable);

    /**
     * Platform admin view across homes. When filterSchedule is true only tickets whose visit
     * starts in [from, to) are returned (calendar). from/to are always non-null to avoid untyped null binds.
     */
    @Query("""
        SELECT t FROM MaintenanceTicket t
        WHERE (:status IS NULL OR t.status = :status)
          AND (:filterSchedule = false OR (t.scheduledStart >= :from AND t.scheduledStart < :to))
    """)
    Page<MaintenanceTicket> findForAdmin(
            @Param("status") MaintenanceTicketStatus status,
            @Param("filterSchedule") boolean filterSchedule,
            @Param("from") OffsetDateTime from,
            @Param("to") OffsetDateTime to,
            Pageable pageable);

    @Query("""
        SELECT COUNT(d) > 0 FROM MaintenanceTicketDevice d
        WHERE d.device.id = :deviceId AND d.ticket.status IN :statuses
    """)
    boolean existsDeviceInStatuses(
            @Param("deviceId") UUID deviceId,
            @Param("statuses") Collection<MaintenanceTicketStatus> statuses);

    @Query("""
        SELECT COUNT(t) > 0 FROM MaintenanceTicket t
        WHERE t.id <> :ticketId
          AND t.status IN :statuses
          AND t.scheduledStart < :endAt
          AND t.scheduledEnd > :startAt
    """)
    boolean existsScheduleOverlap(
            @Param("ticketId") UUID ticketId,
            @Param("startAt") OffsetDateTime startAt,
            @Param("endAt") OffsetDateTime endAt,
            @Param("statuses") Collection<MaintenanceTicketStatus> statuses);
}
