package com.xiye.formaspace.file.service;

import com.xiye.formaspace.common.exception.*;
import com.xiye.formaspace.file.config.FileStorageProperties;
import java.io.IOException;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.imageio.ImageIO;
import javax.imageio.stream.MemoryCacheImageInputStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.web.multipart.MultipartFile;

@Component
@RequiredArgsConstructor
public class SourceImageValidator {
    private final FileStorageProperties limits;

    public ValidatedImage validate(MultipartFile file) {
        if (file.isEmpty()) {
            throw new BusinessException(ErrorCode.INVALID_IMAGE);
        }
        if (file.getSize() > limits.getMaxUploadBytes()) {
            throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
        }
        String name = displayName(file.getOriginalFilename());
        String extension = name.substring(name.lastIndexOf('.') + 1).toLowerCase(Locale.ROOT);
        String expected = switch (extension) {
            case "jpg", "jpeg" -> "JPEG";
            case "png" -> "PNG";
            default -> throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE);
        };
        String mime = expected.equals("JPEG") ? "image/jpeg" : "image/png";
        if (file.getContentType() != null && !file.getContentType().equalsIgnoreCase(mime)) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE);
        }
        try (var input = new MemoryCacheImageInputStream(file.getInputStream())) {
            var readers = ImageIO.getImageReaders(input);
            if (!readers.hasNext()) {
                throw new BusinessException(ErrorCode.INVALID_IMAGE);
            }
            var reader = readers.next();
            try {
                if (!expected.equalsIgnoreCase(reader.getFormatName())) {
                    throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE);
                }
                reader.setInput(input, true, true);
                int width = reader.getWidth(0);
                int height = reader.getHeight(0);
                if (width <= 0 || height <= 0 || (long) width * height > limits.getMaxImagePixels()) {
                    throw new BusinessException(ErrorCode.IMAGE_PIXEL_LIMIT_EXCEEDED);
                }
                var warning = new AtomicBoolean();
                reader.addIIOReadWarningListener((source, message) -> warning.set(true));
                var decoded = reader.read(0);
                try {
                    if (decoded == null || warning.get()) {
                        throw new BusinessException(ErrorCode.INVALID_IMAGE);
                    }
                } finally {
                    if (decoded != null) { decoded.flush(); }
                }
            } finally { reader.dispose(); }
        } catch (IOException | IllegalArgumentException exception) {
            throw new BusinessException(ErrorCode.INVALID_IMAGE);
        }
        return new ValidatedImage(name, mime, expected.equals("JPEG") ? "jpg" : "png");
    }

    private String displayName(String filename) {
        String name = filename == null ? "" : filename.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).replaceAll("[\\p{Cntrl}]", "").strip();
        if (name.isBlank() || name.length() > 255 || !name.contains(".")) {
            throw new BusinessException(ErrorCode.UNSUPPORTED_FILE_TYPE);
        }
        return name;
    }

    public record ValidatedImage(String originalName, String contentType, String extension) {}
}
