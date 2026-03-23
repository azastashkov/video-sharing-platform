package com.videosharing.common.dto;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record VideoMetadata(
        UUID id,
        String originalFilename,
        VideoStatus status,
        List<TranscodedFileReference> transcodedFiles,
        Instant createdAt
) implements Serializable {

    public record TranscodedFileReference(
            String resolution,
            String url,
            long fileSize
    ) implements Serializable {}
}
