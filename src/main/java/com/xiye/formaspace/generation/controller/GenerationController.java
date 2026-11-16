package com.xiye.formaspace.generation.controller;

import com.xiye.formaspace.auth.security.CurrentUser;
import com.xiye.formaspace.common.response.*;
import com.xiye.formaspace.generation.dto.*;
import com.xiye.formaspace.generation.service.*;
import com.xiye.formaspace.generation.vo.*;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/generation")
@RequiredArgsConstructor
public class GenerationController {
    private final GenerationTaskService tasks;
    private final GenerationCapabilitiesService capabilities;
    private final CurrentUser currentUser;

    @GetMapping("/capabilities")
    public ApiResponse<GenerationCapabilitiesVO> capabilities() {
        return ApiResponse.success(capabilities.capabilities());
    }

    @PostMapping("/tasks")
    public ResponseEntity<ApiResponse<GenerationTaskVO>> create(@RequestHeader("Idempotency-Key") String key,
            @Valid @RequestBody CreateGenerationDTO request) {
        var creation = tasks.create(currentUser.id(), key, request);
        return ResponseEntity.status(creation.created() ? HttpStatus.CREATED : HttpStatus.OK)
                .cacheControl(CacheControl.noStore()).body(ApiResponse.success(creation.task()));
    }

    @GetMapping("/tasks/{id}")
    public ApiResponse<GenerationTaskVO> detail(@PathVariable long id) {
        return ApiResponse.success(tasks.detail(id, currentUser.id()));
    }

    @GetMapping("/tasks")
    public ApiResponse<PageResult<GenerationTaskVO>> page(@Valid @ModelAttribute GenerationQueryDTO query) {
        return ApiResponse.success(tasks.page(currentUser.id(), query));
    }
}
