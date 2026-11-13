package com.hesta.backend.security;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Filter xác thực API key cho endpoint nội bộ /internal/**.
 * Dùng cho giao tiếp service-to-service giữa voice-service và Spring Boot.
 *
 * Cơ chế: so khớp header X-Internal-Api-Key với giá trị từ biến môi trường
 * INTERNAL_API_KEY. Chỉ áp dụng cho request tới /internal/**.
 *
 * Không qua AuthenticationManager thông thường — đây là cơ chế riêng
 * cho giao tiếp nội bộ (Mục 2.3 VOICE_AI_IMPLEMENTATION_GUIDE.md).
 */
@Component
@Slf4j
public class InternalApiKeyFilter extends OncePerRequestFilter {

    private static final String API_KEY_HEADER = "X-Internal-Api-Key";
    private static final String INTERNAL_PATH_PREFIX = "/internal/";

    @Value("${hesta.internal.api-key:}")
    private String internalApiKey;

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {

        String path = request.getRequestURI();

        // Chỉ check cho /internal/** requests
        if (!path.startsWith(INTERNAL_PATH_PREFIX)) {
            filterChain.doFilter(request, response);
            return;
        }

        // Kiểm tra API key đã được cấu hình chưa
        if (internalApiKey == null || internalApiKey.isBlank()) {
            log.error("INTERNAL_API_KEY chưa được cấu hình! Từ chối tất cả request /internal/**.");
            sendUnauthorized(response, "Internal API key not configured on server");
            return;
        }

        // Kiểm tra header
        String providedKey = request.getHeader(API_KEY_HEADER);
        if (providedKey == null || providedKey.isBlank()) {
            log.warn("Request /internal/** thiếu header {}: {} {}", API_KEY_HEADER,
                    request.getMethod(), path);
            sendUnauthorized(response, "Missing " + API_KEY_HEADER + " header");
            return;
        }

        // So khớp key
        if (!internalApiKey.equals(providedKey)) {
            log.warn("Request /internal/** sai API key: {} {}", request.getMethod(), path);
            sendForbidden(response, "Invalid API key");
            return;
        }

        // Key hợp lệ → cho qua
        log.debug("Internal API key OK: {} {}", request.getMethod(), path);
        filterChain.doFilter(request, response);
    }

    private void sendUnauthorized(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"code\":401,\"message\":\"" + message + "\"}"
        );
    }

    private void sendForbidden(HttpServletResponse response, String message) throws IOException {
        response.setStatus(HttpServletResponse.SC_FORBIDDEN);
        response.setContentType("application/json");
        response.getWriter().write(
                "{\"code\":403,\"message\":\"" + message + "\"}"
        );
    }
}
