package com.xiye.formaspace.file;

import com.xiye.formaspace.common.exception.*;
import com.xiye.formaspace.file.config.FileStorageProperties;
import com.xiye.formaspace.file.service.SourceImageValidator;
import com.xiye.formaspace.file.storage.LocalFileStorage;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.file.*;
import java.util.Arrays;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockMultipartFile;

import static org.assertj.core.api.Assertions.*;

class SourceImageAndStorageTests {
    @TempDir Path temporary;
    private final FileStorageProperties properties = new FileStorageProperties();

    static byte[] image(String format) throws IOException {
        var bytes = new ByteArrayOutputStream();
        var image = new BufferedImage(4, 4, BufferedImage.TYPE_INT_RGB);
        ImageIO.write(image, format, bytes);
        image.flush();
        return bytes.toByteArray();
    }

    @ParameterizedTest
    @ValueSource(strings = {"png", "jpg"})
    void validatesDecodeAndReadsExactlyStoredImage(String format) throws Exception {
        var bytes = image(format);
        String type = format.equals("png") ? "image/png" : "image/jpeg";
        var upload = new MockMultipartFile("file", "C:\\fakepath\\chair." + format, type, bytes);
        var validated = new SourceImageValidator(properties).validate(upload);
        assertThat(validated.originalName()).isEqualTo("chair." + format);
        properties.setRoot(temporary.toRealPath());
        var storage = new LocalFileStorage(properties);
        var stored = storage.save(upload.getInputStream(), validated.extension(), properties.getMaxUploadBytes());
        assertThat(stored.key()).doesNotContain("chair");
        try (var content = storage.open(stored.key())) {
            assertThat(content.sizeBytes()).isEqualTo(bytes.length);
            assertThat(content.stream().readAllBytes()).isEqualTo(bytes);
        }
        storage.delete(stored.key());
        assertThat(Files.exists(temporary.resolve(stored.key()))).isFalse();
    }

    @Test
    void rejectsSpoofedCorruptUnsupportedOversizedAndHighPixelImages() throws Exception {
        var validator = new SourceImageValidator(properties);
        byte[] png = image("png");
        assertError(() -> validator.validate(new MockMultipartFile("file", "a.jpg", "image/jpeg", png)), ErrorCode.UNSUPPORTED_FILE_TYPE);
        assertError(() -> validator.validate(new MockMultipartFile("file", "a.webp", "image/webp", png)), ErrorCode.UNSUPPORTED_FILE_TYPE);
        assertError(() -> validator.validate(new MockMultipartFile("file", "a.png", "image/png", new byte[] {1,2,3})), ErrorCode.INVALID_IMAGE);
        assertError(() -> validator.validate(new MockMultipartFile("file", "a.png", "image/png", Arrays.copyOf(png, png.length / 2))), ErrorCode.INVALID_IMAGE);
        properties.setMaxImagePixels(15);
        assertError(() -> validator.validate(new MockMultipartFile("file", "a.png", "image/png", png)), ErrorCode.IMAGE_PIXEL_LIMIT_EXCEEDED);
        properties.setMaxUploadBytes(1);
        assertError(() -> validator.validate(new MockMultipartFile("file", "a.png", "image/png", png)), ErrorCode.FILE_TOO_LARGE);
    }

    @Test
    void oversizeStorageWriteDoesNotLeavePartialFile() throws Exception {
        properties.setRoot(temporary.toRealPath());
        var storage = new LocalFileStorage(properties);
        assertThatThrownBy(() -> storage.save(new ByteArrayInputStream(new byte[30]), "png", 20))
                .isInstanceOfSatisfying(BusinessException.class, error -> assertThat(error.getErrorCode()).isEqualTo(ErrorCode.FILE_TOO_LARGE));
        try (var children = Files.list(temporary)) { assertThat(children.count()).isZero(); }
    }

    @Test
    void rejectsTraversalAndSymlinksForBothRootAndStoredFile() throws Exception {
        Path root = Files.createDirectory(temporary.resolve("root")).toRealPath();
        Path outside = Files.writeString(temporary.resolve("outside.txt"), "private");
        properties.setRoot(root);
        var storage = new LocalFileStorage(properties);
        assertThatThrownBy(() -> storage.open("../outside.txt")).isInstanceOf(IOException.class);
        String key = "00000000-0000-0000-0000-000000000001.png";
        Files.createSymbolicLink(root.resolve(key), outside);
        assertThatThrownBy(() -> storage.open(key)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> storage.delete(key)).isInstanceOf(IOException.class);
        Path linkedRoot = temporary.resolve("linked-root");
        Files.createSymbolicLink(linkedRoot, root);
        properties.setRoot(linkedRoot);
        assertThatThrownBy(() -> new LocalFileStorage(properties)).isInstanceOf(IllegalStateException.class);
        assertThat(Files.readString(outside)).isEqualTo("private");
    }

    private void assertError(Runnable action, ErrorCode code) {
        assertThatThrownBy(action::run).isInstanceOfSatisfying(BusinessException.class,
                error -> assertThat(error.getErrorCode()).isEqualTo(code));
    }
}
