package com.hesta.backend.controller;

import com.hesta.backend.config.SecurityConfig;
import com.hesta.backend.dto.response.TwinLayoutResponse;
import com.hesta.backend.security.CustomAccessDeniedHandler;
import com.hesta.backend.security.CustomAuthenticationEntryPoint;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.security.JwtAuthenticationFilter;
import com.hesta.backend.security.JwtTokenProvider;
import com.hesta.backend.security.CustomUserDetailsService;
import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.service.TwinLayoutService;
import com.hesta.backend.support.TwinFixtures;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.security.servlet.UserDetailsServiceAutoConfiguration;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(controllers = TwinLayoutController.class,
        excludeAutoConfiguration = UserDetailsServiceAutoConfiguration.class)
@Import({SecurityConfig.class, JwtAuthenticationFilter.class, CustomAuthenticationEntryPoint.class,
        CustomAccessDeniedHandler.class})
class TwinLayoutControllerTest {
    @Autowired MockMvc mvc;
    @MockitoBean TwinLayoutService service;
    @MockitoBean JwtTokenProvider jwtTokenProvider;
    @MockitoBean CustomUserDetailsService userDetailsService;
    private final UUID userId = TwinFixtures.id(500);
    private final UUID homeId = TwinFixtures.id(1);

    @Test
    void getLayout_authenticated_returnsApiResponseAndEmptyLayout() throws Exception {
        when(service.getLayout(userId, homeId)).thenReturn(new TwinLayoutResponse(homeId, 0, List.of(), List.of()));
        mvc.perform(get(url()).with(authentication(principal())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.homeId").value(homeId.toString()))
                .andExpect(jsonPath("$.result.revision").value(0))
                .andExpect(jsonPath("$.result.rooms").isArray())
                .andExpect(jsonPath("$.result.nodes").isArray());
        verify(service).getLayout(userId, homeId);
    }

    @Test
    void putLayout_authenticated_passesTypedRequestToService() throws Exception {
        when(service.saveLayout(eq(userId), eq(homeId), any())).thenReturn(
                new TwinLayoutResponse(homeId, 1, List.of(), List.of()));
        mvc.perform(put(url()).with(authentication(principal()))
                        .contentType("application/json")
                        .content("""
                                {"expectedRevision":0,"rooms":[],"nodes":[]}
                                """))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.revision").value(1));
        verify(service).saveLayout(eq(userId), eq(homeId), any());
    }

    @Test
    void getLayout_anonymous_returns401() throws Exception {
        mvc.perform(get(url())).andExpect(status().isUnauthorized());
    }

    @Test
    void putLayout_missingGeometry_returnsExistingValidationEnvelope() throws Exception {
        mvc.perform(put(url()).with(authentication(principal()))
                        .contentType("application/json")
                        .content("""
                                {"expectedRevision":0,"rooms":[{"roomId":"00000000-0000-4000-8000-000000000012","x":null,"y":0.1,"width":0.2,"height":0.2}],"nodes":[]}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(1125));
    }

    private UsernamePasswordAuthenticationToken principal() {
        var details = new CustomUserDetails(userId, "layout@example.com", "", AccountStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        return new UsernamePasswordAuthenticationToken(details, null, details.getAuthorities());
    }

    private String url() { return "/api/v1/homes/" + homeId + "/twin-layout"; }
}
