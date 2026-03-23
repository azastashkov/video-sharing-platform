package com.videosharing.common.dto;

import java.util.List;
import java.util.UUID;

public record TranscodingCompletion(
        UUID videoId,
        String originalFilename,
        List<TranscodedFileInfo> transcodedFiles
) {}
