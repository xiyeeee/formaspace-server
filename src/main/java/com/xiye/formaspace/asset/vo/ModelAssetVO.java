package com.xiye.formaspace.asset.vo;

import java.time.Instant;

public record ModelAssetVO(String id, String generationTaskId, String name, String format, long sizeBytes,
        boolean simulated, String modelUrl, String thumbnailUrl, Instant createdAt) {}
