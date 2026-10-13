package com.hesta.backend.controller;

import com.hesta.backend.config.SecurityConfig;
import com.hesta.backend.dto.response.TwinHomeSnapshotResponse;
import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.security.*;
import com.hesta.backend.service.TwinSnapshotService;
import com.hesta.backend.support.TwinFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = TwinSnapshotController.class,
        excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, CustomAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class})
class TwinSnapshotControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean TwinSnapshotService service;
    @MockitoBean JwtTokenProvider jwtTokenProvider;
    @MockitoBean CustomUserDetailsService userDetailsService;
    private final UUID userId = TwinFixtures.id(500);
    private final UUID homeId = TwinFixtures.id(1);

    @Test
    void getSnapshot_authenticated_usesPrincipalAndExistingEnvelope() throws Exception {
        when(service.getSnapshot(userId, homeId))
                .thenReturn(new TwinHomeSnapshotResponse(homeId, "My Home", List.of(), List.of(), List.of()));
        mvc.perform(get(url()).param("userId", TwinFixtures.id(999).toString())
                        .with(authentication(principal("USER"))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.homeId").value(homeId.toString()))
                .andExpect(jsonPath("$.result.rooms").isArray());
        verify(service).getSnapshot(userId, homeId);
    }

    @Test
    void getSnapshot_anonymous_returns401() throws Exception {
        mvc.perform(get(url())).andExpect(status().isUnauthorized());
        verifyNoInteractions(service);
    }

    @Test
    void getSnapshot_platformAdminWithoutHomeMembership_returns403() throws Exception {
        when(service.getSnapshot(userId, homeId)).thenThrow(new AppException(ErrorCode.UNAUTHORIZED));
        mvc.perform(get(url()).with(authentication(principal("ADMIN"))))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));
    }

    private UsernamePasswordAuthenticationToken principal(String role) {
        var principal = new CustomUserDetails(userId, "twin@example.com", "", AccountStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority("ROLE_" + role)));
        return new UsernamePasswordAuthenticationToken(principal, null, principal.getAuthorities());
    }

    private String url() {
        return "/api/v1/homes/" + homeId + "/twin";
    }
}
