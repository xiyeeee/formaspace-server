package com.xiye.formaspace.generation.realtime;

import com.xiye.formaspace.auth.security.CurrentUser;
import com.xiye.formaspace.common.response.ApiResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@RequestMapping("/api/generation/tasks")
public class GenerationPreviewController {
    private final GenerationPreviewService previews;
    private final CurrentUser user;
    @GetMapping("/{id}/previews/{version}")
    public ApiResponse<GenerationPreviewService.PreviewVO> read(@PathVariable long id,@PathVariable int version) {
        return ApiResponse.success(previews.read(id,version,user.id()));
    }
}
