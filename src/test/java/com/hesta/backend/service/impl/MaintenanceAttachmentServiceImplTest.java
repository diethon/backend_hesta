package com.hesta.backend.service.impl;

import com.hesta.backend.dto.response.MaintenanceAttachmentResponse;
import com.hesta.backend.entity.Home;
import com.hesta.backend.entity.MaintenanceTicketAttachment;
import com.hesta.backend.entity.User;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.maintenance.assistant.ImageSanitizer;
import com.hesta.backend.repository.MaintenanceTicketAttachmentRepository;
import com.hesta.backend.repository.UserRepository;
import com.hesta.backend.service.CloudinaryService;
import com.hesta.backend.service.HomeAuthorizationService;
import com.hesta.backend.service.MaintenanceRateLimiter;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockMultipartFile;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class MaintenanceAttachmentServiceImplTest {

    @Mock private HomeAuthorizationService homeAuthorizationService;
    @Mock private UserRepository userRepository;
    @Mock private MaintenanceTicketAttachmentRepository attachmentRepository;
    @Mock private CloudinaryService cloudinaryService;
    @Mock private MaintenanceRateLimiter rateLimiter;

    private final ImageSanitizer imageSanitizer = new ImageSanitizer();
    private MaintenanceAttachmentServiceImpl service;

    private final UUID userId = UUID.randomUUID();
    private final UUID homeId = UUID.randomUUID();
    private Home home;
    private User user;

    @BeforeEach
    void setUp() {
        service = new MaintenanceAttachmentServiceImpl(
                homeAuthorizationService, userRepository, attachmentRepository, cloudinaryService, rateLimiter, imageSanitizer);
        home = new Home();
        home.setId(homeId);
        user = User.builder().id(userId).fullName("User").email("u@x.vn").build();
        when(homeAuthorizationService.requireAccess(userId, homeId)).thenReturn(home);
        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
    }

    @Test
    void uploadAcceptsValidJpegMagicBytes() throws IOException {
        byte[] jpeg = new byte[16];
        jpeg[0] = (byte) 0xFF;
        jpeg[1] = (byte) 0xD8;
        jpeg[2] = (byte) 0xFF;
        MockMultipartFile file = new MockMultipartFile("file", "photo.jpg", "application/octet-stream", jpeg);

        when(cloudinaryService.uploadMaintenanceAttachment(any(), eq("image/jpeg")))
                .thenReturn(new CloudinaryService.UploadResult("hesta/maintenance-attachments/abc", "https://signed/abc.jpg"));
        when(attachmentRepository.save(any())).thenAnswer(inv -> {
            MaintenanceTicketAttachment a = inv.getArgument(0);
            a.setId(UUID.randomUUID());
            return a;
        });

        MaintenanceAttachmentResponse resp = service.upload(userId, homeId, file);
        assertThat(resp.contentType()).isEqualTo("image/jpeg");
        assertThat(resp.url()).startsWith("/api/v1/homes/" + homeId + "/maintenance-attachments/").endsWith("/content");
        assertThat(resp.sizeBytes()).isEqualTo(16);
    }

    @Test
    void loadContentStreamsForAuthorizedMemberAndAdminAndBlocksForeignDraft() throws IOException {
        UUID attId = UUID.randomUUID();
        com.hesta.backend.entity.MaintenanceTicket ticket = new com.hesta.backend.entity.MaintenanceTicket();
        ticket.setId(UUID.randomUUID());
        MaintenanceTicketAttachment attached = MaintenanceTicketAttachment.builder()
                .id(attId).home(home).ticket(ticket).uploadedBy(user)
                .storagePublicId("hesta/maintenance-attachments/img1").url("internal").contentType("image/png").sizeBytes(4).build();
        when(attachmentRepository.findById(attId)).thenReturn(Optional.of(attached));
        when(cloudinaryService.downloadMaintenanceAttachment("hesta/maintenance-attachments/img1", "image/png"))
                .thenReturn(new byte[]{1, 2, 3, 4});

        var memberContent = service.loadContent(userId, false, homeId, attId);
        assertThat(memberContent.contentType()).isEqualTo("image/png");
        assertThat(memberContent.bytes()).containsExactly(1, 2, 3, 4);

        var adminContent = service.loadContent(UUID.randomUUID(), true, homeId, attId);
        assertThat(adminContent.bytes()).containsExactly(1, 2, 3, 4);

        // Unattached draft owned by another user must be blocked
        UUID draftId = UUID.randomUUID();
        MaintenanceTicketAttachment foreignDraft = MaintenanceTicketAttachment.builder()
                .id(draftId).home(home).ticket(null)
                .uploadedBy(User.builder().id(UUID.randomUUID()).build())
                .storagePublicId("p").url("u").contentType("image/jpeg").sizeBytes(4).build();
        when(attachmentRepository.findById(draftId)).thenReturn(Optional.of(foreignDraft));
        assertThatThrownBy(() -> service.loadContent(userId, false, homeId, draftId))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MAINTENANCE_ATTACHMENT_NOT_FOUND);
    }

    @Test
    void uploadRejectsSpoofedContentType() {
        // Claims to be image/jpeg but contains plain text bytes.
        MockMultipartFile spoofed = new MockMultipartFile(
                "file", "fake.jpg", "image/jpeg", "not a real jpeg file at all".getBytes());

        assertThatThrownBy(() -> service.upload(userId, homeId, spoofed))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MAINTENANCE_ATTACHMENT_TYPE_INVALID);
    }

    @Test
    void uploadRejectsFileOver5Mb() {
        byte[] huge = new byte[(int) (MaintenanceAttachmentServiceImpl.MAX_SIZE_BYTES + 1)];
        huge[0] = (byte) 0x89;
        huge[1] = 0x50;
        huge[2] = 0x4E;
        huge[3] = 0x47;
        huge[4] = 0x0D;
        huge[5] = 0x0A;
        huge[6] = 0x1A;
        huge[7] = 0x0A;
        MockMultipartFile file = new MockMultipartFile("file", "big.png", "image/png", huge);

        assertThatThrownBy(() -> service.upload(userId, homeId, file))
                .isInstanceOf(AppException.class)
                .extracting("errorCode").isEqualTo(ErrorCode.MAINTENANCE_ATTACHMENT_TOO_LARGE);
    }

    @Test
    void detectsPngAndWebpMagicBytes() {
        byte[] png = new byte[]{(byte) 0x89, 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0};
        byte[] webp = new byte[]{'R', 'I', 'F', 'F', 0, 0, 0, 0, 'W', 'E', 'B', 'P'};
        assertThat(MaintenanceAttachmentServiceImpl.detectImageMimeType(png)).isEqualTo("image/png");
        assertThat(MaintenanceAttachmentServiceImpl.detectImageMimeType(webp)).isEqualTo("image/webp");
    }
}
