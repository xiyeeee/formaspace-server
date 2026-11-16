package com.xiye.formaspace.file.vo;


public record FileUploadVO(String id, String originalName, String contentType, long sizeBytes, String previewUrl) {}
