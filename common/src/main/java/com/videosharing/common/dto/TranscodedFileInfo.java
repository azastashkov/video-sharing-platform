package com.videosharing.common.dto;

public record TranscodedFileInfo(
        String resolution,
        String minioKey,
        long fileSize
) {}
