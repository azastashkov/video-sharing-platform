package com.videosharing.common.dto;

import java.util.UUID;

public record TranscodingTask(
        UUID videoId,
        String originalFilename,
        String originalMinioKey
) {}
