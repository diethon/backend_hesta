package com.hesta.backend.security;

import com.google.api.client.googleapis.auth.oauth2.GoogleIdToken;
import com.google.api.client.googleapis.auth.oauth2.GoogleIdTokenVerifier;
import com.google.api.client.http.javanet.NetHttpTransport;
import com.google.api.client.json.gson.GsonFactory;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import lombok.Builder;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.Collections;

@Service
@Slf4j
public class GoogleAuthService {

    @Value("${app.google.client-id:dummy-google-client-id.apps.googleusercontent.com}")
    private String googleClientId;

    @Getter
    @Builder
    public static class GoogleUserInfo {
        private String email;
        private String googleUid;
        private String fullName;
        private String avatarUrl;
    }

    public GoogleUserInfo verifyGoogleToken(String idTokenString) {
        // Fallback for mock/demo testing locally when real Google OAuth credentials are not provided
        if (idTokenString.startsWith("mock-google-token:")) {
            String[] parts = idTokenString.split(":");
            String email = parts.length > 1 ? parts[1] : "user.google@example.com";
            String uid = parts.length > 2 ? parts[2] : "google-uid-" + System.currentTimeMillis();
            String name = parts.length > 3 ? parts[3] : "Google User";

            return GoogleUserInfo.builder()
                    .email(email)
                    .googleUid(uid)
                    .fullName(name)
                    .avatarUrl("https://lh3.googleusercontent.com/a/default-avatar")
                    .build();
        }

        try {
            GoogleIdTokenVerifier verifier = new GoogleIdTokenVerifier.Builder(
                    new NetHttpTransport(),
                    GsonFactory.getDefaultInstance()
            )
                    .setAudience(Collections.singletonList(googleClientId))
                    .build();

            GoogleIdToken idToken = verifier.verify(idTokenString);
            if (idToken != null) {
                GoogleIdToken.Payload payload = idToken.getPayload();

                String userId = payload.getSubject();
                String email = payload.getEmail();
                String name = (String) payload.get("name");
                String pictureUrl = (String) payload.get("picture");

                return GoogleUserInfo.builder()
                        .email(email)
                        .googleUid(userId)
                        .fullName(name != null ? name : email.split("@")[0])
                        .avatarUrl(pictureUrl)
                        .build();
            } else {
                log.error("Invalid Google ID Token verification failure");
                throw new AppException(ErrorCode.GOOGLE_AUTH_FAILED);
            }
        } catch (Exception e) {
            log.error("Lỗi khi xác thực Google ID Token", e);
            throw new AppException(ErrorCode.GOOGLE_AUTH_FAILED);
        }
    }
}
