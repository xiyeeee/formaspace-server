package com.xiye.formaspace.file.controller;

import com.xiye.formaspace.auth.security.CurrentUser;
import com.xiye.formaspace.common.enums.FilePurpose;
import com.xiye.formaspace.common.exception.*;
import com.xiye.formaspace.common.response.ApiResponse;
import com.xiye.formaspace.file.service.FileService;
import com.xiye.formaspace.file.vo.FileUploadVO;
import java.io.IOException;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.*;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartHttpServletRequest;

@RestController
@RequestMapping("/api/files")
@RequiredArgsConstructor
public class FileController {
    private final FileService files;
    private final CurrentUser currentUser;

    @PostMapping(value = "/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<ApiResponse<FileUploadVO>> upload(MultipartHttpServletRequest request) {
        var uploads = request.getMultiFileMap();
        if (uploads.size() != 1 || !uploads.containsKey("file") || uploads.get("file").size() != 1) {
            throw new BusinessException(ErrorCode.INVALID_ARGUMENT);
        }
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.success(files.upload(currentUser.id(), uploads.getFirst("file"))));
    }

    @GetMapping("/{id}/content")
    public ResponseEntity<InputStreamResource> content(@PathVariable long id) throws IOException {
        var file = files.requireOwned(id, currentUser.id(), FilePurpose.SOURCE_IMAGE);
        var content = files.open(file);
        return ResponseEntity.ok().contentType(MediaType.parseMediaType(file.getContentType()))
                .contentLength(content.sizeBytes()).header(HttpHeaders.CACHE_CONTROL, "private, no-store")
                .header("X-Content-Type-Options", "nosniff").body(new InputStreamResource(content.stream()));
    }
}
