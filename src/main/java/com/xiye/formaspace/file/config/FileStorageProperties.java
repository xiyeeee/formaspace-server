package com.xiye.formaspace.file.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.nio.file.Path;
import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Getter
@Setter
@Validated
@ConfigurationProperties("formaspace.storage")
public class FileStorageProperties {
    @NotNull
    private Path root = Path.of(System.getProperty("user.home"), ".formaspace", "storage");
    @Min(1)
    private long maxUploadBytes = 10 * 1024 * 1024;
    @Min(1)
    private long maxImagePixels = 16_000_000;
    @Min(1)
    private long maxModelBytes = 100 * 1024 * 1024;
}
