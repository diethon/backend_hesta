package com.hesta.backend.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CloudinaryService {

    private final Cloudinary cloudinary;

    @Value("${hesta.maintenance.attachment.cloudinary-type:authenticated}")
    private String maintenanceDeliveryType;

    public record UploadResult(String publicId, String url) {
    }

    public UploadResult uploadMaintenanceAttachment(byte[] bytes, String detectedMimeType) throws IOException {
        String deliveryType = (maintenanceDeliveryType == null || maintenanceDeliveryType.isBlank())
                ? "authenticated"
                : maintenanceDeliveryType.trim();
        try {
            Map<?, ?> uploadResult = cloudinary.uploader().upload(bytes,
                    ObjectUtils.asMap(
                            "folder", "hesta/maintenance-attachments",
                            "resource_type", "image",
                            "type", deliveryType
                    ));
            Object publicIdObj = uploadResult.get("public_id");
            if (publicIdObj == null || publicIdObj.toString().isBlank()) {
                throw new IOException("Cloudinary không trả về public_id.");
            }
            String publicId = publicIdObj.toString();
            String url;
            if ("authenticated".equalsIgnoreCase(deliveryType) || "private".equalsIgnoreCase(deliveryType)) {
                Object formatObj = uploadResult.get("format");
                String format = formatObj != null ? formatObj.toString() : extensionFor(detectedMimeType);
                url = cloudinary.url()
                        .resourceType("image")
                        .type(deliveryType)
                        .signed(true)
                        .secure(true)
                        .format(format)
                        .generate(publicId);
            } else {
                url = secureUrl(uploadResult);
            }
            return new UploadResult(publicId, url);
        } catch (IOException | RuntimeException e) {
            log.error("Lỗi khi upload ảnh đính kèm bảo trì lên Cloudinary: {}", e.getMessage());
            throw new IOException("Không thể upload ảnh, vui lòng thử lại sau.", e);
        }
    }

    public byte[] downloadMaintenanceAttachment(String publicId, String contentType) throws IOException {
        if (publicId == null || publicId.isBlank()) {
            throw new IOException("Thiếu public_id của ảnh đính kèm.");
        }
        String deliveryType = (maintenanceDeliveryType == null || maintenanceDeliveryType.isBlank())
                ? "authenticated"
                : maintenanceDeliveryType.trim();
        String signedUrl = cloudinary.url()
                .resourceType("image")
                .type(deliveryType)
                .signed("authenticated".equalsIgnoreCase(deliveryType) || "private".equalsIgnoreCase(deliveryType))
                .secure(true)
                .format(extensionFor(contentType))
                .generate(publicId);
        try {
            byte[] bytes = org.springframework.web.client.RestClient.create()
                    .get()
                    .uri(signedUrl)
                    .retrieve()
                    .body(byte[].class);
            if (bytes == null || bytes.length == 0) {
                throw new IOException("Không tải được dữ liệu ảnh từ kho lưu trữ.");
            }
            return bytes;
        } catch (Exception e) {
            log.warn("Không thể tải nội dung ảnh đính kèm {}: {}", publicId, e.getMessage());
            throw new IOException("Không thể tải ảnh đính kèm.", e);
        }
    }

    public void deleteImage(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            return;
        }
        String deliveryType = (maintenanceDeliveryType == null || maintenanceDeliveryType.isBlank())
                ? "authenticated"
                : maintenanceDeliveryType.trim();
        try {
            Map<?, ?> res = cloudinary.uploader().destroy(publicId, ObjectUtils.asMap(
                    "resource_type", "image",
                    "type", deliveryType,
                    "invalidate", true
            ));
            Object resultVal = res != null ? res.get("result") : null;
            if (resultVal != null && !"ok".equals(resultVal.toString()) && !"not found".equals(resultVal.toString())) {
                log.warn("Cloudinary destroy cho {} (type={}) trả kết quả: {}", publicId, deliveryType, resultVal);
            }
        } catch (Exception e) {
            log.warn("Không thể xoá ảnh Cloudinary {}: {}", publicId, e.getMessage());
        }
    }

    private static String extensionFor(String mimeType) {
        if ("image/png".equals(mimeType)) {
            return "png";
        }
        if ("image/webp".equals(mimeType)) {
            return "webp";
        }
        return "jpg";
    }

    public String uploadImage(MultipartFile file, String folderName) throws IOException {
        try {
            Map<?, ?> uploadResult = cloudinary.uploader().upload(file.getBytes(),
                    ObjectUtils.asMap(
                            "folder", "hesta/" + folderName,
                            "resource_type", "image"
                    ));
            return secureUrl(uploadResult);
        } catch (IOException | RuntimeException e) {
            log.error("Lỗi khi upload ảnh lên Cloudinary", e);
            throw new IOException("Không thể upload ảnh, vui lòng thử lại sau.", e);
        }
    }

    public String uploadAvatar(MultipartFile file) throws IOException {
        try {
            // Apply transformations: crop to square, face detection, resize to 256x256
            Map<?, ?> uploadResult = cloudinary.uploader().upload(file.getBytes(),
                    ObjectUtils.asMap(
                            "folder", "hesta/avatars",
                            "resource_type", "image",
                            "transformation", "c_thumb,g_face,h_256,w_256"
                    ));
            return secureUrl(uploadResult);
        } catch (IOException | RuntimeException e) {
            log.error("Lỗi khi upload avatar lên Cloudinary", e);
            throw new IOException("Không thể upload avatar, vui lòng thử lại sau.", e);
        }
    }

    public UploadResult uploadFirmwareBinary(Path filePath, String publicId) throws IOException {
        if (filePath == null || publicId == null || publicId.isBlank()) {
            throw new IOException("Thiếu tệp firmware hoặc publicId.");
        }
        String baseId = publicId.trim();
        if (baseId.toLowerCase(Locale.ROOT).endsWith(".bin")) {
            baseId = baseId.substring(0, baseId.length() - 4);
        }
        String safePublicId = baseId.toLowerCase(Locale.ROOT).endsWith(".ota") ? baseId : baseId + ".ota";

        Path tempOta = null;
        try {
            // Cloudinary blocks ".bin" raw assets by default; stream a ".ota" temp copy instead.
            Path parentDir = filePath.getParent();
            tempOta = (parentDir != null)
                    ? Files.createTempFile(parentDir, "cld-ota-", ".ota")
                    : Files.createTempFile("cld-ota-", ".ota");
            Files.copy(filePath, tempOta, StandardCopyOption.REPLACE_EXISTING);

            Map<?, ?> uploadResult = cloudinary.uploader().upload(tempOta.toFile(),
                    ObjectUtils.asMap(
                            "folder", "hesta/ota-firmware",
                            "public_id", safePublicId,
                            "resource_type", "raw",
                            "type", "upload",
                            "overwrite", true,
                            "use_filename", false
                    ));
            Object returnedPublicId = uploadResult.get("public_id");
            String finalPublicId = returnedPublicId != null && !returnedPublicId.toString().isBlank()
                    ? returnedPublicId.toString()
                    : "hesta/ota-firmware/" + safePublicId;
            return new UploadResult(finalPublicId, secureUrl(uploadResult));
        } catch (IOException | RuntimeException e) {
            log.warn("Không thể upload firmware lên Cloudinary ({}): {}", safePublicId, e.getMessage());
            throw new IOException("Không thể upload firmware lên Cloudinary.", e);
        } finally {
            if (tempOta != null) {
                try {
                    Files.deleteIfExists(tempOta);
                } catch (IOException ignored) {}
            }
        }
    }

    public void deleteFirmwareBinary(String publicId) {
        if (publicId == null || publicId.isBlank()) {
            return;
        }
        try {
            cloudinary.uploader().destroy(publicId.trim(), ObjectUtils.asMap(
                    "resource_type", "raw",
                    "type", "upload",
                    "invalidate", true
            ));
        } catch (Exception e) {
            log.warn("Không thể xóa firmware Cloudinary {}: {}", publicId, e.getMessage());
        }
    }

    private String secureUrl(Map<?, ?> uploadResult) throws IOException {
        Object secureUrl = uploadResult.get("secure_url");
        if (secureUrl == null || secureUrl.toString().isBlank()) {
            throw new IOException("Cloudinary không trả về URL ảnh an toàn.");
        }
        return secureUrl.toString();
    }
}
