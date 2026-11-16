package com.xiye.formaspace.config;

import com.xiye.formaspace.file.config.FileStorageProperties;
import jakarta.servlet.MultipartConfigElement;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
@EnableConfigurationProperties(FileStorageProperties.class)
public class FileStorageConfig {
    @Bean
    public MultipartConfigElement multipartConfigElement(FileStorageProperties storage) {
        return new MultipartConfigElement("", storage.getMaxUploadBytes(),
                Math.addExact(storage.getMaxUploadBytes(), 1024 * 1024), 0);
    }
}
