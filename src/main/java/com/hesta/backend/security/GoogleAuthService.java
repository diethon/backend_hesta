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

    @Value("${app.google.client-id}")
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

                log.info("Google OAuth verified successfully for email: {}", email);

                return GoogleUserInfo.builder()
                        .email(email)
                        .googleUid(userId)
                        .fullName(name != null ? name : email.split("@")[0])
                        .avatarUrl(pictureUrl)
                        .build();
            } else {
                log.error("Invalid Google ID Token — verification returned null");
                throw new AppException(ErrorCode.GOOGLE_AUTH_FAILED);
            }
        } catch (AppException e) {
            throw e;
        } catch (Exception e) {
            log.error("Error verifying Google ID Token", e);
            throw new AppException(ErrorCode.GOOGLE_AUTH_FAILED);
        }
    }
}

