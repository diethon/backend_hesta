package com.hesta.backend.service;

import com.cloudinary.Cloudinary;
import com.cloudinary.utils.ObjectUtils;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CloudinaryService {

    private final Cloudinary cloudinary;

    public String uploadImage(MultipartFile file, String folderName) throws IOException {
        try {
            Map uploadResult = cloudinary.uploader().upload(file.getBytes(),
                    ObjectUtils.asMap(
                            "folder", "hesta/" + folderName,
                            "resource_type", "image"
                    ));
            return uploadResult.get("secure_url").toString();
        } catch (IOException e) {
            log.error("Lỗi khi upload ảnh lên Cloudinary", e);
            throw new IOException("Không thể upload ảnh, vui lòng thử lại sau.", e);
        }
    }

    public String uploadAvatar(MultipartFile file) throws IOException {
        try {
            // Apply transformations: crop to square, face detection, resize to 256x256
            Map uploadResult = cloudinary.uploader().upload(file.getBytes(),
                    ObjectUtils.asMap(
                            "folder", "hesta/avatars",
                            "resource_type", "image",
                            "transformation", "c_thumb,g_face,h_256,w_256"
                    ));
            return uploadResult.get("secure_url").toString();
        } catch (IOException e) {
            log.error("Lỗi khi upload avatar lên Cloudinary", e);
            throw new IOException("Không thể upload avatar, vui lòng thử lại sau.", e);
        }
    }
}
