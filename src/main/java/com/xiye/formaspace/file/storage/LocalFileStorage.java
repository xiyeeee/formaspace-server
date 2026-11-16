package com.xiye.formaspace.file.storage;

import com.xiye.formaspace.common.exception.*;
import com.xiye.formaspace.file.config.FileStorageProperties;
import java.io.*;
import java.nio.channels.Channels;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

@lombok.extern.slf4j.Slf4j
@Component
public class LocalFileStorage implements FileStorage {
    private final Path root;

    public LocalFileStorage(FileStorageProperties properties) throws IOException {
        root = properties.getRoot().toAbsolutePath().normalize();
        for (Path current = root; current != null; current = current.getParent()) {
            if (Files.isSymbolicLink(current)) {
                throw new IllegalStateException("存储目录及其父目录不能是符号链接");
            }
        }
        Files.createDirectories(root);
        verifyRoot();
    }

    @Override
    public StoredFile save(InputStream source, String extension, long maximumBytes) throws IOException {
        if (!Set.of("jpg", "png", "glb").contains(extension)) {
            throw new IllegalArgumentException("Unsupported storage extension");
        }
        verifyRoot();
        String key = UUID.randomUUID() + "." + extension;
        Path destination = resolve(key);
        Path temporary = Files.createTempFile(root, ".upload-", ".tmp");
        long size = 0;
        try {
            try (var output = Files.newOutputStream(temporary, StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)) {
                byte[] buffer = new byte[8192];
                int length;
                while ((length = source.read(buffer)) != -1) {
                    size = Math.addExact(size, length);
                    if (size > maximumBytes) {
                        throw new BusinessException(ErrorCode.FILE_TOO_LARGE);
                    }
                    output.write(buffer, 0, length);
                }
            }
            verifyRoot();
            // Never fall back to a non-atomic move: an incomplete file must not become visible.
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE);
            return new StoredFile(key, size);
        } finally {
            try { Files.deleteIfExists(temporary); }
            catch (IOException exception) {
                log.error("Temporary file cleanup failed: key={}, type={}", temporary.getFileName(), exception.getClass().getSimpleName());
            }
        }
    }

    @Override
    public StoredContent open(String key) throws IOException {
        Path path = resolve(key);
        var channel = Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS));
        try {
            var attributes = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
            if (!attributes.isRegularFile()) {
                throw new NoSuchFileException("Stored file unavailable");
            }
            return new StoredContent(Channels.newInputStream(channel), channel.size());
        } catch (Exception exception) {
            channel.close();
            throw exception;
        }
    }

    @Override
    public void delete(String key) throws IOException {
        Files.deleteIfExists(resolve(key));
    }

    private Path resolve(String key) throws IOException {
        verifyRoot();
        if (key == null || !key.matches("[a-f0-9-]{36}\\.(jpg|png|glb)")) {
            throw new NoSuchFileException("Invalid storage identifier");
        }
        Path path = root.resolve(key).normalize();
        if (!path.getParent().equals(root) || Files.isSymbolicLink(path)) {
            throw new NoSuchFileException("Stored file unavailable");
        }
        return path;
    }

    private void verifyRoot() throws IOException {
        if (Files.isSymbolicLink(root) || !root.toRealPath().equals(root)) {
            throw new IOException("Storage directory is unavailable");
        }
    }
}
