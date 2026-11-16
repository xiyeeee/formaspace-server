package com.xiye.formaspace.generation.inference;

import com.xiye.formaspace.common.exception.ErrorCode;
import com.xiye.formaspace.file.config.FileStorageProperties;
import com.xiye.formaspace.generation.config.GenerationProperties;
import java.io.*;
import java.nio.file.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "formaspace.generation", name = "mode", havingValue = "MOCK")
public class MockModelInferenceClient implements ModelInferenceClient {
    private final Path demo;
    private final GlbValidator validator;
    private final FileStorageProperties limits;

    public MockModelInferenceClient(GenerationProperties properties, GlbValidator validator,
            FileStorageProperties limits) throws IOException {
        this.validator = validator;
        this.limits = limits;
        demo = properties.getMockDemoGlb() == null || properties.getMockDemoGlb().toString().isBlank()
                ? null : properties.getMockDemoGlb().toAbsolutePath().normalize();
        if (demo != null) { validator.validate(demo, limits.getMaxModelBytes()); }
    }

    @Override
    public void submit(Submission submission) throws IOException {
        // Validate the controlled input is readable; no inference, background removal or texturing is performed.
        try (var input = submission.sourceImage().content().getInputStream()) {
            if (input.read() == -1) { throw new IOException("Source image unavailable"); }
        }
    }

    @Override
    public QueryResult query(long internalTaskId) {
        return demo == null ? new QueryResult(true, null, ErrorCode.MOCK_RESULT_NOT_CONFIGURED)
                : new QueryResult(true, "configured-demo", null);
    }

    @Override
    public InputStream openResult(String resultReference) throws IOException {
        if (demo == null || !"configured-demo".equals(resultReference)) { throw new IOException("Mock result unavailable"); }
        validator.validate(demo, limits.getMaxModelBytes());
        return Files.newInputStream(demo, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    }
}
