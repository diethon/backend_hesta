package com.hesta.backend.maintenance.assistant;

import com.hesta.backend.service.impl.MaintenanceAttachmentServiceImpl;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.imageio.ImageIO;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.Optional;

/**
 * Tái mã hóa ảnh thuần trong RAM (không ghi đĩa):
 * - Kiểm tra magic bytes
 * - Giảm cạnh dài tối đa xuống maxDimensionPx (mặc định 1280px)
 * - Loại bỏ toàn bộ EXIF / GPS / XMP metadata
 */
@Component
@Slf4j
public class ImageSanitizer {

    public record SanitizedImage(byte[] bytes, String mimeType, int width, int height) {
    }

    public Optional<SanitizedImage> sanitize(byte[] rawBytes, int maxDimensionPx) {
        String detectedMime = MaintenanceAttachmentServiceImpl.detectImageMimeType(rawBytes);
        if (detectedMime == null) {
            return Optional.empty();
        }
        try {
            BufferedImage source = ImageIO.read(new ByteArrayInputStream(rawBytes));
            if (source == null) {
                // Ví dụ WebP trên JVM không cài plugin ImageIO WebP
                return Optional.empty();
            }
            int srcW = source.getWidth();
            int srcH = source.getHeight();
            int targetW = srcW;
            int targetH = srcH;
            int limit = Math.max(64, maxDimensionPx);
            if (srcW > limit || srcH > limit) {
                double scale = Math.min((double) limit / srcW, (double) limit / srcH);
                targetW = Math.max(1, (int) Math.round(srcW * scale));
                targetH = Math.max(1, (int) Math.round(srcH * scale));
            }

            boolean isPng = "image/png".equals(detectedMime);
            int imageType = isPng ? BufferedImage.TYPE_INT_ARGB : BufferedImage.TYPE_INT_RGB;
            BufferedImage clean = new BufferedImage(targetW, targetH, imageType);
            Graphics2D g2d = clean.createGraphics();
            try {
                g2d.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                g2d.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
                if (!isPng) {
                    g2d.setColor(Color.WHITE);
                    g2d.fillRect(0, 0, targetW, targetH);
                }
                g2d.drawImage(source, 0, 0, targetW, targetH, null);
            } finally {
                g2d.dispose();
            }

            String format = isPng ? "png" : "jpg";
            String outMime = isPng ? "image/png" : "image/jpeg";
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            boolean written = ImageIO.write(clean, format, baos);
            if (!written) {
                return Optional.empty();
            }
            return Optional.of(new SanitizedImage(baos.toByteArray(), outMime, targetW, targetH));
        } catch (IOException | RuntimeException e) {
            log.warn("ImageSanitizer failed to re-encode image: {}", e.getMessage());
            return Optional.empty();
        }
    }
}
