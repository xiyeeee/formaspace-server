package com.xiye.formaspace.generation.vo;

import java.time.Instant;
import com.xiye.formaspace.generation.enums.GenerationStatus;
import com.xiye.formaspace.generation.parameter.*;

public record GenerationTaskVO(String id, String sourceFileId, GenerationStatus status, Integer progress,
        boolean simulated, RequestedGenerationParameters requestedParameters, ResolvedGenerationParameters resolvedParameters,
        String assetId, String errorCode, String errorMessage, Instant createdAt, Instant startedAt, Instant finishedAt) {}
