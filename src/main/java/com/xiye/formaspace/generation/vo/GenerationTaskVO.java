package com.xiye.formaspace.generation.vo;

import java.time.Instant;
import com.xiye.formaspace.generation.enums.GenerationStatus;

public record GenerationTaskVO(String id, String sourceFileId, GenerationStatus status, Integer progress, String errorCode, String errorMessage, String assetId, Instant createdAt, Instant finishedAt) {}
