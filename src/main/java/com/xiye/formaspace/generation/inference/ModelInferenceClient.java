package com.xiye.formaspace.generation.inference;

import com.xiye.formaspace.common.exception.ErrorCode;
import com.xiye.formaspace.generation.parameter.ResolvedGenerationParameters;
import java.io.IOException;
import java.io.InputStream;
import java.time.Instant;
import org.springframework.core.io.InputStreamSource;

/** Internal contract; remote references must be persisted before waiting for execution. */
public interface ModelInferenceClient {
    SubmissionResult submit(Submission submission) throws IOException;
    QueryResult query(String remoteReference) throws IOException;
    InputStream openResult(String resultReference) throws IOException;

    record SourceImage(long fileId, String contentType, InputStreamSource content) {}
    record Submission(long internalTaskId, ResolvedGenerationParameters parameters, SourceImage sourceImage, Instant deadlineAt) {}
    record SubmissionResult(String remoteReference) {}
    enum QueryState { RUNNING, SUCCEEDED, FAILED }
    record QueryResult(boolean completed, String resultReference, ErrorCode failure) {
        public QueryState state() { return !completed ? QueryState.RUNNING : failure == null ? QueryState.SUCCEEDED : QueryState.FAILED; }
    }
}
