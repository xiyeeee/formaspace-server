package com.xiye.formaspace.file.storage;

import java.io.IOException;
import java.io.InputStream;

/** Storage keys are opaque server-generated identifiers, never client filenames or URLs. */
public interface FileStorage {
    StoredFile save(InputStream source, String extension, long maximumBytes) throws IOException;
    StoredContent open(String key) throws IOException;
    void delete(String key) throws IOException;

    record StoredFile(String key, long sizeBytes) {}
    record StoredContent(InputStream stream, long sizeBytes) implements AutoCloseable {
        @Override
        public void close() throws IOException { stream.close(); }
    }
}
