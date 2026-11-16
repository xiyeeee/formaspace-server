package com.xiye.formaspace.generation;

import com.xiye.formaspace.asset.controller.ModelAssetController;
import com.xiye.formaspace.asset.service.ModelAssetService;
import com.xiye.formaspace.auth.security.*;
import com.xiye.formaspace.common.enums.*;
import com.xiye.formaspace.common.exception.*;
import com.xiye.formaspace.config.*;
import com.xiye.formaspace.file.config.FileStorageProperties;
import com.xiye.formaspace.file.controller.FileController;
import com.xiye.formaspace.file.entity.FileEntity;
import com.xiye.formaspace.file.service.FileService;
import com.xiye.formaspace.file.storage.FileStorage;
import com.xiye.formaspace.file.vo.FileUploadVO;
import com.xiye.formaspace.generation.config.GenerationProperties;
import com.xiye.formaspace.generation.controller.GenerationController;
import com.xiye.formaspace.generation.enums.GenerationMode;
import com.xiye.formaspace.generation.service.*;
import com.xiye.formaspace.testsupport.MediaFixtures;
import com.xiye.formaspace.user.mapper.UserMapper;
import com.xiye.formaspace.user.vo.UserVO;
import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.multipart.MaxUploadSizeExceededException;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = {FileController.class, GenerationController.class, ModelAssetController.class},
        properties = {"formaspace.email-code.hmac-key=test-only", "formaspace.cors.allowed-origins=http://localhost:5173"})
@Import({SecurityConfig.class, WebConfig.class, SecurityJsonResponse.class, CurrentUser.class, ActiveUserAuthenticationConverter.class})
class GenerationHttpTests {
    @Autowired private MockMvc mvc;
    @MockitoBean private FileService files;
    @MockitoBean private ModelAssetService assets;
    @MockitoBean private GenerationTaskService tasks;
    @MockitoBean private GenerationCapabilitiesService capabilities;
    @MockitoBean private JwtDecoder decoder;
    @MockitoBean private UserMapper users;

    private Authentication identity() {
        return UsernamePasswordAuthenticationToken.authenticated(new UserPrincipal(7L,
                new UserVO("7", "user@example.com", "user_1", "user_1", null, UserStatus.ACTIVE, Instant.EPOCH)), null, java.util.List.of());
    }

    @Test
    void allBusinessRoutesRequireAuthentication() throws Exception {
        for (String path : new String[] {"/api/files/1/content", "/api/generation/capabilities", "/api/generation/tasks", "/api/generation/tasks/1",
                "/api/assets/1", "/api/assets/1/model", "/api/assets/1/download"}) {
            mvc.perform(get(path)).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("UNAUTHORIZED"));
        }
        mvc.perform(multipart("/api/files/upload").file(new MockMultipartFile("file", "x.png", "image/png", MediaFixtures.png())))
                .andExpect(status().isUnauthorized());
        mvc.perform(post("/api/generation/tasks").contentType("application/json").content("{}"))
                .andExpect(status().isUnauthorized());
        verifyNoInteractions(files, assets, tasks);
    }

    @Test
    void uploadsExactlyOneFileAndDoesNotTrustUserIdParameter() throws Exception {
        var upload = new MockMultipartFile("file", "x.png", "image/png", MediaFixtures.png());
        when(files.upload(eq(7L), any())).thenReturn(new FileUploadVO("12", "x.png", "image/png", upload.getSize(), null));
        mvc.perform(multipart("/api/files/upload").file(upload).param("userId", "999").with(authentication(identity())))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.data.id").value("12"))
                .andExpect(jsonPath("$.data.storageKey").doesNotExist());
        verify(files).upload(eq(7L), any());
        mvc.perform(multipart("/api/files/upload").file(upload).file(upload).with(authentication(identity())))
                .andExpect(status().isBadRequest());
        mvc.perform(multipart("/api/files/upload").file(new MockMultipartFile("other", "x.png", "image/png", MediaFixtures.png()))
                        .with(authentication(identity())))
                .andExpect(status().isBadRequest());
    }

    @Test
    void streamingSourceAndModelSetCorrectHeadersAndSafeAttachmentName() throws Exception {
        byte[] bytes = MediaFixtures.png();
        var source = new FileEntity(); source.setContentType("image/png");
        when(files.requireOwned(12L, 7L, FilePurpose.SOURCE_IMAGE)).thenReturn(source);
        when(files.open(source)).thenAnswer(invocation -> new FileStorage.StoredContent(new ByteArrayInputStream(bytes), bytes.length));
        mvc.perform(get("/api/files/12/content").with(authentication(identity())))
                .andExpect(status().isOk()).andExpect(content().bytes(bytes)).andExpect(content().contentType("image/png"))
                .andExpect(header().longValue("Content-Length", bytes.length))
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"));
        var model = new FileEntity();
        when(assets.modelFile(18L, 7L)).thenReturn(model);
        when(files.open(model)).thenAnswer(invocation -> new FileStorage.StoredContent(new ByteArrayInputStream(MediaFixtures.glb()), MediaFixtures.glb().length));
        mvc.perform(get("/api/assets/18/download").with(authentication(identity())))
                .andExpect(status().isOk()).andExpect(content().contentType("model/gltf-binary"))
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"model-18.glb\""))
                .andExpect(header().string("Cache-Control", "private, no-store"));
    }

    @Test
    void ownershipErrorsAreUniform404ForImagesTasksModelsAndDownloads() throws Exception {
        when(files.requireOwned(anyLong(), eq(7L), any())).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));
        when(tasks.detail(anyLong(), eq(7L))).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));
        when(assets.detail(anyLong(), eq(7L))).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));
        when(assets.modelFile(anyLong(), eq(7L))).thenThrow(new BusinessException(ErrorCode.NOT_FOUND));
        for (String path : new String[] {"/api/files/12/content", "/api/generation/tasks/12", "/api/assets/12", "/api/assets/12/model", "/api/assets/12/download"}) {
            mvc.perform(get(path).with(authentication(identity())))
                    .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOT_FOUND"));
        }
    }

    @Test
    void capabilitiesHaveHonestModeAndPresetMarkers() throws Exception {
        var properties = new GenerationProperties(); properties.setMode(GenerationMode.MOCK);
        when(capabilities.capabilities()).thenReturn(new GenerationCapabilitiesService(properties, new FileStorageProperties()).capabilities());
        mvc.perform(get("/api/generation/capabilities").with(authentication(identity())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.mode").value("MOCK"))
                .andExpect(jsonPath("$.data.simulated").value(true)).andExpect(jsonPath("$.data.modelId").isEmpty())
                .andExpect(jsonPath("$.data.qualityPresets.STANDARD.simulated").value(true))
                .andExpect(jsonPath("$.data.textureSupported").value(false))
                .andExpect(jsonPath("$.data.uploadConstraints.maximumPixels").value(16000000));
    }

    @Test
    void validatesIdempotencyHeaderNestedParametersAndPageSize() throws Exception {
        mvc.perform(post("/api/generation/tasks").with(authentication(identity()))
                        .contentType("application/json").content("{\"sourceFileId\":\"1\"}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/generation/tasks").with(authentication(identity())).header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json").content("{\"sourceFileId\":\"1\",\"advancedOptions\":{\"seed\":-1}}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/generation/tasks").with(authentication(identity())).header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json").content("{\"sourceFileId\":\"1\",\"unknown\":1}"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/api/generation/tasks?size=101").with(authentication(identity()))).andExpect(status().isBadRequest());
        verifyNoInteractions(tasks);
    }

    @Test
    void creationAndReplayHaveDistinctStatusesAndMultipartLimitUses413() throws Exception {
        when(tasks.create(eq(7L), anyString(), any())).thenReturn(new GenerationTaskService.Creation(null, true));
        mvc.perform(post("/api/generation/tasks").with(authentication(identity())).header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json").content("{\"sourceFileId\":\"1\"}"))
                .andExpect(status().isCreated());
        when(tasks.create(eq(7L), anyString(), any())).thenReturn(new GenerationTaskService.Creation(null, false));
        mvc.perform(post("/api/generation/tasks").with(authentication(identity())).header("Idempotency-Key", UUID.randomUUID())
                        .contentType("application/json").content("{\"sourceFileId\":\"1\"}"))
                .andExpect(status().isOk());
        when(files.upload(eq(7L), any())).thenThrow(new MaxUploadSizeExceededException(1));
        mvc.perform(multipart("/api/files/upload").file(new MockMultipartFile("file", "x.png", "image/png", MediaFixtures.png()))
                        .with(authentication(identity())))
                .andExpect(status().is(413)).andExpect(jsonPath("$.code").value("FILE_TOO_LARGE"));
    }

    @Test
    void corsPreflightAllowsIdempotencyHeaderWithoutCookies() throws Exception {
        mvc.perform(options("/api/generation/tasks").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "Idempotency-Key,Authorization,Content-Type"))
                .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
    }
}
