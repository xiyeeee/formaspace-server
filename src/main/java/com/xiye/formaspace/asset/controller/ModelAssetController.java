package com.xiye.formaspace.asset.controller;

import com.xiye.formaspace.asset.service.ModelAssetService;
import com.xiye.formaspace.asset.vo.ModelAssetVO;
import com.xiye.formaspace.auth.security.CurrentUser;
import com.xiye.formaspace.common.response.ApiResponse;
import com.xiye.formaspace.file.service.FileService;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/assets")
@RequiredArgsConstructor
public class ModelAssetController {
    private final ModelAssetService assets;
    private final FileService files;
    private final CurrentUser currentUser;

    @GetMapping("/{id}")
    public ApiResponse<ModelAssetVO> detail(@PathVariable long id) {
        return ApiResponse.success(assets.detail(id, currentUser.id()));
    }

    @GetMapping("/{id}/model")
    public ResponseEntity<InputStreamResource> model(@PathVariable long id) { return content(id, false); }

    @GetMapping("/{id}/download")
    public ResponseEntity<InputStreamResource> download(@PathVariable long id) { return content(id, true); }

    private ResponseEntity<InputStreamResource> content(long id, boolean attachment) {
        var file = assets.modelFile(id, currentUser.id());
        var content = files.open(file);
        var response = ResponseEntity.ok().contentType(MediaType.parseMediaType("model/gltf-binary"))
                .contentLength(content.sizeBytes()).header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff");
        if (attachment) {
            response.header(HttpHeaders.CONTENT_DISPOSITION,
                    ContentDisposition.attachment().filename("model-" + id + ".glb").build().toString());
        }
        return response.body(new InputStreamResource(content.stream()));
    }
}
