package com.xiye.formaspace.generation.inference;

import com.xiye.formaspace.common.exception.ErrorCode;
import com.xiye.formaspace.generation.parameter.ResolvedGenerationParameters;
import java.io.IOException;
import java.io.InputStream;
import org.springframework.core.io.InputStreamSource;

/** Internal Java contract only; does not claim to be the Tencent Python HTTP API. */
public interface ModelInferenceClient {
    void submit(Submission submission) throws IOException;
    QueryResult query(long internalTaskId);
    InputStream openResult(String resultReference) throws IOException;

    record SourceImage(long fileId, String contentType, InputStreamSource content) {}
    record Submission(long internalTaskId, ResolvedGenerationParameters parameters, SourceImage sourceImage) {}
    record QueryResult(boolean completed, String resultReference, ErrorCode failure) {}
}
