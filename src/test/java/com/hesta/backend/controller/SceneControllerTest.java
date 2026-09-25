package com.hesta.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hesta.backend.dto.request.CreateSceneRequest;
import com.hesta.backend.dto.request.ReorderSceneActionsRequest;
import com.hesta.backend.dto.request.SceneActionRequest;
import com.hesta.backend.dto.request.UpdateSceneRequest;
import com.hesta.backend.dto.response.SceneActionResponse;
import com.hesta.backend.dto.response.SceneResponse;
import com.hesta.backend.enums.AccountStatus;
import com.hesta.backend.exception.AppException;
import com.hesta.backend.exception.ErrorCode;
import com.hesta.backend.security.CustomUserDetails;
import com.hesta.backend.service.SceneService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class SceneControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private SceneService sceneService;

    private UUID userId;
    private UUID homeId;
    private UUID sceneId;
    private UUID actionId;
    private UUID deviceId;
    private UsernamePasswordAuthenticationToken authentication;
    private SceneResponse sceneResponse;

    @BeforeEach
    void setUp() {
        userId = UUID.randomUUID();
        homeId = UUID.randomUUID();
        sceneId = UUID.randomUUID();
        actionId = UUID.randomUUID();
        deviceId = UUID.randomUUID();
        CustomUserDetails principal = new CustomUserDetails(
                userId,
                "owner@example.com",
                "",
                AccountStatus.ACTIVE,
                List.of(new SimpleGrantedAuthority("ROLE_USER")));
        authentication = new UsernamePasswordAuthenticationToken(
                principal, null, principal.getAuthorities());
        sceneResponse = SceneResponse.builder()
                .id(sceneId)
                .homeId(homeId)
                .name("Sleep Mode")
                .description("Prepare the home for sleep")
                .enabled(true)
                .actions(List.of(SceneActionResponse.builder()
                        .id(actionId)
                        .targetDeviceId(deviceId)
                        .targetDeviceName("Bedroom light")
                        .action("TURN_OFF")
                        .order(0)
                        .build()))
                .build();
    }

    @Test
    void authenticatedOwnerCanCreateScene() throws Exception {
        when(sceneService.createScene(eq(userId), eq(homeId), any(CreateSceneRequest.class)))
                .thenReturn(sceneResponse);

        mockMvc.perform(post(sceneCollectionUrl())
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(createRequest())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000))
                .andExpect(jsonPath("$.result.name").value("Sleep Mode"))
                .andExpect(jsonPath("$.result.actions[0].action").value("TURN_OFF"));
    }

    @Test
    void authenticatedOwnerCanListScenes() throws Exception {
        when(sceneService.getScenesForHome(userId, homeId)).thenReturn(List.of(sceneResponse));

        mockMvc.perform(get(sceneCollectionUrl()).with(authentication(authentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result[0].id").value(sceneId.toString()));
    }

    @Test
    void authenticatedOwnerCanViewOrderedDetail() throws Exception {
        when(sceneService.getScene(userId, homeId, sceneId)).thenReturn(sceneResponse);

        mockMvc.perform(get(sceneUrl()).with(authentication(authentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.actions[0].order").value(0))
                .andExpect(jsonPath("$.result.actions[0].targetDeviceId").value(deviceId.toString()));
    }

    @Test
    void authenticatedOwnerCanUpdateScene() throws Exception {
        when(sceneService.updateScene(eq(userId), eq(homeId), eq(sceneId), any(UpdateSceneRequest.class)))
                .thenReturn(sceneResponse);

        mockMvc.perform(put(sceneUrl())
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(UpdateSceneRequest.builder()
                                .name("Sleep Mode")
                                .description("Updated")
                                .enabled(true)
                                .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.message").value("Cập nhật kịch bản thành công"));
    }

    @Test
    void authenticatedOwnerCanDeleteScene() throws Exception {
        mockMvc.perform(delete(sceneUrl()).with(authentication(authentication)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(1000));
    }

    @Test
    void unauthorizedUserCannotManageAnotherHomeScene() throws Exception {
        when(sceneService.getScene(userId, homeId, sceneId))
                .thenThrow(new AppException(ErrorCode.UNAUTHORIZED));

        mockMvc.perform(get(sceneUrl()).with(authentication(authentication)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHORIZED.getCode()));
    }

    @Test
    void unauthenticatedRequestIsRejectedBySecurity() throws Exception {
        mockMvc.perform(get(sceneCollectionUrl()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(ErrorCode.UNAUTHENTICATED.getCode()));
    }

    @Test
    void authenticatedOwnerCanAddAndRemoveAction() throws Exception {
        SceneActionResponse action = sceneResponse.getActions().getFirst();
        when(sceneService.addAction(eq(userId), eq(homeId), eq(sceneId), any(SceneActionRequest.class)))
                .thenReturn(action);

        mockMvc.perform(post(sceneUrl() + "/actions")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(SceneActionRequest.builder()
                                .targetDeviceId(deviceId)
                                .action("TURN_OFF")
                                .order(0)
                                .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.id").value(actionId.toString()));

        mockMvc.perform(delete(sceneUrl() + "/actions/" + actionId)
                        .with(authentication(authentication)))
                .andExpect(status().isOk());
    }

    @Test
    void authenticatedOwnerCanReorderActions() throws Exception {
        when(sceneService.reorderActions(
                eq(userId), eq(homeId), eq(sceneId), any(ReorderSceneActionsRequest.class)))
                .thenReturn(sceneResponse);

        mockMvc.perform(put(sceneUrl() + "/actions/reorder")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(ReorderSceneActionsRequest.builder()
                                .actionIds(List.of(actionId))
                                .build())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.result.actions[0].order").value(0));
    }

    @Test
    void invalidActionAndValueErrorsAreReturnedClearly() throws Exception {
        when(sceneService.addAction(eq(userId), eq(homeId), eq(sceneId), any(SceneActionRequest.class)))
                .thenThrow(new AppException(ErrorCode.SCENE_ACTION_INVALID))
                .thenThrow(new AppException(ErrorCode.SCENE_ACTION_VALUE_INVALID));

        SceneActionRequest request = SceneActionRequest.builder()
                .targetDeviceId(deviceId)
                .action("INVALID")
                .order(0)
                .build();
        mockMvc.perform(post(sceneUrl() + "/actions")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.SCENE_ACTION_INVALID.getCode()));

        request.setAction("SET_BRIGHTNESS");
        request.setValue(objectMapper.valueToTree(200));
        mockMvc.perform(post(sceneUrl() + "/actions")
                        .with(authentication(authentication))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value(ErrorCode.SCENE_ACTION_VALUE_INVALID.getCode()));
    }

    private CreateSceneRequest createRequest() {
        return CreateSceneRequest.builder()
                .name("Sleep Mode")
                .description("Prepare the home for sleep")
                .enabled(true)
                .actions(List.of(SceneActionRequest.builder()
                        .targetDeviceId(deviceId)
                        .action("TURN_OFF")
                        .order(0)
                        .build()))
                .build();
    }

    private String sceneCollectionUrl() {
        return "/api/v1/homes/" + homeId + "/scenes";
    }

    private String sceneUrl() {
        return sceneCollectionUrl() + "/" + sceneId;
    }
}
