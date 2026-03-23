package com.videosharing.completion.service;

import com.videosharing.common.dto.TranscodedFileInfo;
import com.videosharing.common.dto.TranscodingCompletion;
import com.videosharing.common.dto.VideoStatus;
import com.videosharing.completion.entity.TranscodedFileEntity;
import com.videosharing.completion.entity.VideoEntity;
import com.videosharing.completion.repository.TranscodedFileRepository;
import com.videosharing.completion.repository.VideoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
public class CompletionService {

    private static final Logger log = LoggerFactory.getLogger(CompletionService.class);
    private static final String CACHE_PREFIX = "video:metadata:";

    private final VideoRepository videoRepository;
    private final TranscodedFileRepository transcodedFileRepository;
    private final StringRedisTemplate redisTemplate;

    public CompletionService(VideoRepository videoRepository,
                             TranscodedFileRepository transcodedFileRepository,
                             StringRedisTemplate redisTemplate) {
        this.videoRepository = videoRepository;
        this.transcodedFileRepository = transcodedFileRepository;
        this.redisTemplate = redisTemplate;
    }

    @Transactional
    public void handleCompletion(TranscodingCompletion completion) {
        VideoEntity video = videoRepository.findById(completion.videoId())
                .orElseThrow(() -> new IllegalStateException("Video not found: " + completion.videoId()));

        // Insert transcoded file records
        for (TranscodedFileInfo fileInfo : completion.transcodedFiles()) {
            TranscodedFileEntity entity = new TranscodedFileEntity(
                    UUID.randomUUID(),
                    video,
                    fileInfo.resolution(),
                    fileInfo.minioKey(),
                    fileInfo.fileSize()
            );
            transcodedFileRepository.save(entity);
        }

        // Update video status
        video.setStatus(VideoStatus.COMPLETED);
        videoRepository.save(video);

        // Invalidate cache so next read gets fresh data
        try {
            redisTemplate.delete(CACHE_PREFIX + completion.videoId());
        } catch (Exception e) {
            log.warn("Failed to invalidate cache for video {}: {}", completion.videoId(), e.getMessage());
        }

        log.info("Completion handled: videoId={}, transcodedFiles={}", completion.videoId(), completion.transcodedFiles().size());
    }
}
