package com.xiye.formaspace;

import com.xiye.formaspace.auth.dto.RegisterDTO;
import com.xiye.formaspace.common.exception.*;
import com.xiye.formaspace.common.response.*;
import com.xiye.formaspace.config.WebConfig;
import com.xiye.formaspace.health.controller.HealthController;
import jakarta.validation.Valid;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.*;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(controllers = HealthController.class, properties = {
        "formaspace.cors.allowed-origins=http://localhost:5173"})
@Import({WebConfig.class, FormaspaceServerApplicationTests.ProbeController.class})
@ExtendWith(OutputCaptureExtension.class)
class FormaspaceServerApplicationTests {
    @Autowired
    private MockMvc mvc;

    @Test
    void healthUsesUnifiedResponse() throws Exception {
        mvc.perform(get("/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.message").value("success"))
                .andExpect(jsonPath("$.data.status").value("UP"))
                .andExpect(jsonPath("$.data.application").value("formaspace-server"));
    }

    @Test
    void idsAreStringsAndTimesHaveUtcOffset() throws Exception {
        mvc.perform(get("/test/task"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.id").isString())
                .andExpect(jsonPath("$.data.id").value("9223372036854775807"))
                .andExpect(jsonPath("$.data.createdAt").value("2026-11-16T01:02:03Z"));
    }

    @Test
    void preservesNotFoundAndMethodNotAllowed() throws Exception {
        mvc.perform(get("/api/not-found")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("HTTP_404"));
        mvc.perform(post("/api/health")).andExpect(status().isMethodNotAllowed())
                .andExpect(header().exists("Allow"))
                .andExpect(jsonPath("$.code").value("HTTP_405"));
    }

    @Test
    void validationBindingJsonAndConversionReturn400() throws Exception {
        mvc.perform(post("/test/register").contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        mvc.perform(post("/test/register").contentType(MediaType.APPLICATION_JSON).content("{"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"));
        mvc.perform(get("/test/page").param("page", "not-a-number"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/test/page").param("size", "101"))
                .andExpect(status().isBadRequest());
        mvc.perform(get("/test/id/not-a-number")).andExpect(status().isBadRequest());
    }

    @Test
    void mismatchedPasswordsDoNotEchoRequest() throws Exception {
        mvc.perform(post("/test/register").contentType(MediaType.APPLICATION_JSON).content("""
                {"email":"user@example.com","username":"user_1","verificationCode":"012345",
                 "password":"secret-password","confirmPassword":"another-password"}
                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_ARGUMENT"))
                .andExpect(content().string(org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("secret-password"))));
    }

    @Test
    void businessAndUnexpectedErrorsAreSafe(CapturedOutput output) throws Exception {
        mvc.perform(get("/test/conflict")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CONFLICT"));
        mvc.perform(get("/test/error")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(jsonPath("$.message").value("服务暂时不可用，请稍后重试"));
        assertThat(output).contains("IllegalStateException").doesNotContain("secret-SQL-password");
    }

    @Test
    void corsAllowsOnlyConfiguredOriginWithoutCookies() throws Exception {
        mvc.perform(options("/api/health").header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET")
                        .header("Access-Control-Request-Headers", "Authorization,Content-Type"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"))
                .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"));
        mvc.perform(options("/api/health").header("Origin", "https://untrusted.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden())
                .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
    }

    // Test-only routes exercise HTTP infrastructure; never included in the application artifact.
    @RestController
    static class ProbeController {
        @PostMapping("/test/register")
        ApiResponse<Void> register(@Valid @RequestBody RegisterDTO request) {
            return ApiResponse.success(null);
        }

        @GetMapping("/test/page")
        ApiResponse<Void> page(@Valid @ModelAttribute PageQuery query) {
            return ApiResponse.success(null);
        }

        @GetMapping("/test/id/{id}")
        ApiResponse<Long> id(@PathVariable Long id) {
            return ApiResponse.success(id);
        }

        @GetMapping("/test/task")
        ApiResponse<com.xiye.formaspace.generation.vo.GenerationTaskVO> task() {
            return ApiResponse.success(new com.xiye.formaspace.generation.vo.GenerationTaskVO(
                    "9223372036854775807", "1", com.xiye.formaspace.generation.enums.GenerationStatus.PENDING,
                    null, null, null, null, java.time.Instant.parse("2026-11-16T01:02:03Z"), null));
        }

        @GetMapping("/test/conflict")
        void conflict() {
            throw new BusinessException(ErrorCode.CONFLICT);
        }

        @GetMapping("/test/error")
        void error() {
            throw new IllegalStateException("secret-SQL-password");
        }
    }
}
