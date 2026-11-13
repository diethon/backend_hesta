package com.hesta.backend.service;

import com.hesta.backend.entity.User;
import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.enums.AuthProvider;
import com.hesta.backend.enums.PlatformRole;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.repository.UserRepository;
import com.hesta.backend.service.impl.UserServiceImpl;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.io.IOException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

    @Mock
    private UserRepository userRepository;

    @Mock
    private PasswordEncoder passwordEncoder;

    @Mock
    private CloudinaryService cloudinaryService;

    @InjectMocks
    private UserServiceImpl userService;

    @Test
    void uploadAvatar_withValidImage_shouldUploadAndPersistUrl() throws IOException {
        UUID userId = UUID.randomUUID();
        User user = user(userId);
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.png", "image/png", new byte[] { 1, 2, 3 });
        String uploadedUrl = "https://cdn.example.com/avatar.png";

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(cloudinaryService.uploadAvatar(file)).thenReturn(uploadedUrl);

        var response = userService.uploadAvatar(userId, file);

        assertThat(response.getAvatarUrl()).isEqualTo(uploadedUrl);
        assertThat(user.getAvatarUrl()).isEqualTo(uploadedUrl);
        verify(userRepository).save(user);
    }

    @Test
    void uploadAvatar_withEmptyFile_shouldRejectBeforeDatabaseAccess() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.png", "image/png", new byte[0]);

        assertThatThrownBy(() -> userService.uploadAvatar(UUID.randomUUID(), file))
                .isInstanceOfSatisfying(AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.AVATAR_FILE_REQUIRED));
        verifyNoInteractions(userRepository, cloudinaryService);
    }

    @Test
    void uploadAvatar_withUnsupportedContentType_shouldRejectBeforeUpload() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.svg", "image/svg+xml", new byte[] { 1 });

        assertThatThrownBy(() -> userService.uploadAvatar(UUID.randomUUID(), file))
                .isInstanceOfSatisfying(AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.AVATAR_FILE_TYPE_INVALID));
        verifyNoInteractions(userRepository, cloudinaryService);
    }

    @Test
    void uploadAvatar_withFileLargerThanFiveMegabytes_shouldRejectBeforeUpload() {
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.jpg", "image/jpeg", new byte[5 * 1024 * 1024 + 1]);

        assertThatThrownBy(() -> userService.uploadAvatar(UUID.randomUUID(), file))
                .isInstanceOfSatisfying(AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.AVATAR_FILE_TOO_LARGE));
        verifyNoInteractions(userRepository, cloudinaryService);
    }

    @Test
    void uploadAvatar_whenCloudinaryFails_shouldReturnExpectedFailure() throws IOException {
        UUID userId = UUID.randomUUID();
        User user = user(userId);
        MockMultipartFile file = new MockMultipartFile(
                "file", "avatar.webp", "image/webp", new byte[] { 1, 2, 3 });

        when(userRepository.findById(userId)).thenReturn(Optional.of(user));
        when(cloudinaryService.uploadAvatar(file)).thenThrow(new IOException("upload failed"));

        assertThatThrownBy(() -> userService.uploadAvatar(userId, file))
                .isInstanceOfSatisfying(AppException.class,
                        exception -> assertThat(exception.getErrorCode())
                                .isEqualTo(ErrorCode.AVATAR_UPLOAD_FAILED));
    }

    private User user(UUID userId) {
        return User.builder()
                .id(userId)
                .fullName("Avatar Test")
                .email("avatar@example.com")
                .provider(AuthProvider.LOCAL)
                .platformRole(PlatformRole.USER)
                .status(AccountStatus.ACTIVE)
                .build();
    }
}
