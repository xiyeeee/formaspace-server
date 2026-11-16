package com.xiye.formaspace.generation.inference;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.SeekableByteChannel;
import java.nio.file.*;
import java.util.Set;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.ObjectMapper;

@Component
@RequiredArgsConstructor
public class GlbValidator {
    private final ObjectMapper json;

    public void validate(Path path, long maximumBytes) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || !Files.isReadable(path)) {
            throw new IOException("Demo GLB is not readable");
        }
        try (var channel = Files.newByteChannel(path, Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS))) {
            long size = channel.size();
            if (size < 20 || size > maximumBytes) { throw new IOException("Invalid GLB size"); }
            var header = read(channel, 12);
            if (header.getInt() != 0x46546C67 || header.getInt() != 2 || Integer.toUnsignedLong(header.getInt()) != size) {
                throw new IOException("Invalid GLB header");
            }
            int chunkIndex = 0;
            while (channel.position() < size) {
                if (++chunkIndex > 1024 || size - channel.position() < 8) { throw new IOException("Invalid GLB chunks"); }
                var chunk = read(channel, 8);
                long length = Integer.toUnsignedLong(chunk.getInt());
                int type = chunk.getInt();
                if (length % 4 != 0 || length > size - channel.position()) { throw new IOException("Invalid GLB chunk length"); }
                if (chunkIndex == 1) {
                    if (type != 0x4E4F534A || length > 4 * 1024 * 1024) { throw new IOException("Invalid GLB JSON chunk"); }
                    try {
                        var tree = json.readTree(read(channel, (int) length).array());
                        if (!"2.0".equals(tree.path("asset").path("version").asString())) { throw new IOException("Invalid glTF asset"); }
                        for (String field : new String[] {"buffers", "images"}) {
                            for (var resource : tree.path(field)) {
                                if (resource.has("uri") && !resource.get("uri").asString().startsWith("data:")) {
                                    throw new IOException("GLB must be self-contained");
                                }
                            }
                        }
                    } catch (RuntimeException exception) { throw new IOException("Invalid GLB JSON"); }
                } else { channel.position(channel.position() + length); }
            }
        }
    }

    private ByteBuffer read(SeekableByteChannel channel, int length) throws IOException {
        var bytes = ByteBuffer.allocate(length).order(ByteOrder.LITTLE_ENDIAN);
        while (bytes.hasRemaining()) {
            if (channel.read(bytes) == -1) { throw new IOException("Truncated GLB"); }
        }
        return bytes.flip();
    }
}
