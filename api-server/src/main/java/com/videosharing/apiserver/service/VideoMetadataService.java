package com.videosharing.apiserver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videosharing.apiserver.entity.TranscodedFileEntity;
import com.videosharing.apiserver.entity.VideoEntity;
import com.videosharing.apiserver.repository.TranscodedFileRepository;
import com.videosharing.apiserver.repository.VideoRepository;
import com.videosharing.common.dto.VideoMetadata;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class VideoMetadataService {

    private static final String CACHE_KEY_PREFIX = "video:metadata:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final VideoRepository videoRepository;
    private final TranscodedFileRepository transcodedFileRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;

    public VideoMetadataService(
            VideoRepository videoRepository,
            TranscodedFileRepository transcodedFileRepository,
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper) {
        this.videoRepository = videoRepository;
        this.transcodedFileRepository = transcodedFileRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
    }

    public Optional<VideoMetadata> getVideoMetadata(UUID videoId, String requestBaseUrl) {
        String cacheKey = CACHE_KEY_PREFIX + videoId;

        // Check Redis cache first
        String cachedJson = redisTemplate.opsForValue().get(cacheKey);
        if (cachedJson != null) {
            try {
                return Optional.of(objectMapper.readValue(cachedJson, VideoMetadata.class));
            } catch (JsonProcessingException e) {
                // Cache miss on deserialization error, fall through to DB
            }
        }

        // Cache miss - read from DB
        Optional<VideoEntity> videoOpt = videoRepository.findById(videoId);
        if (videoOpt.isEmpty()) {
            return Optional.empty();
        }

        VideoEntity video = videoOpt.get();
        VideoMetadata metadata = toVideoMetadata(video, requestBaseUrl);

        // Cache result with TTL
        try {
            String json = objectMapper.writeValueAsString(metadata);
            redisTemplate.opsForValue().set(cacheKey, json, CACHE_TTL);
        } catch (JsonProcessingException e) {
            // Log and continue without caching
        }

        return Optional.of(metadata);
    }

    public List<VideoMetadata> listAllVideos(String requestBaseUrl) {
        return videoRepository.findAll().stream()
                .map(video -> toVideoMetadata(video, requestBaseUrl))
                .toList();
    }

    private VideoMetadata toVideoMetadata(VideoEntity video, String baseUrl) {
        List<TranscodedFileEntity> transcodedFiles = transcodedFileRepository.findByVideoId(video.getId());

        List<VideoMetadata.TranscodedFileReference> fileReferences = transcodedFiles.stream()
                .map(tf -> new VideoMetadata.TranscodedFileReference(
                        tf.getResolution(),
                        baseUrl + "/api/videos/stream?file=" + video.getOriginalFilename() + "&resolution=" + tf.getResolution(),
                        tf.getFileSize() != null ? tf.getFileSize() : 0L
                ))
                .toList();

        return new VideoMetadata(
                video.getId(),
                video.getOriginalFilename(),
                video.getStatus(),
                fileReferences,
                video.getCreatedAt()
        );
    }
}
