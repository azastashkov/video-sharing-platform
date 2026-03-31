package com.videosharing.apiserver;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.videosharing.apiserver.entity.TranscodedFileEntity;
import com.videosharing.apiserver.entity.VideoEntity;
import com.videosharing.apiserver.repository.TranscodedFileRepository;
import com.videosharing.apiserver.repository.VideoRepository;
import com.videosharing.apiserver.service.VideoMetadataService;
import com.videosharing.common.dto.VideoMetadata;
import com.videosharing.common.dto.VideoStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoMetadataServiceTest {

    @Mock
    private VideoRepository videoRepository;

    @Mock
    private TranscodedFileRepository transcodedFileRepository;

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOperations;

    private VideoMetadataService metadataService;

    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());

        when(redisTemplate.opsForValue()).thenReturn(valueOperations);

        metadataService = new VideoMetadataService(
                videoRepository,
                transcodedFileRepository,
                redisTemplate,
                objectMapper
        );
    }

    @Test
    void getVideoMetadata_returnsCachedResult() throws Exception {
        UUID videoId = UUID.randomUUID();
        VideoMetadata cached = new VideoMetadata(videoId, "video.mp4", VideoStatus.COMPLETED, List.of(), java.time.Instant.now());
        String cachedJson = objectMapper.writeValueAsString(cached);

        when(valueOperations.get("video:metadata:" + videoId)).thenReturn(cachedJson);

        Optional<VideoMetadata> result = metadataService.getVideoMetadata(videoId, "http://localhost:8080");

        assertThat(result).isPresent();
        assertThat(result.get().id()).isEqualTo(videoId);

        // Verify DB never called
        verify(videoRepository, never()).findById(any());
    }

    @Test
    void getVideoMetadata_cacheMiss_readsFromDb() throws Exception {
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, "video.mp4", "originals/" + videoId + "/video.mp4");
        video.setStatus(VideoStatus.COMPLETED);

        UUID transcodedId = UUID.randomUUID();
        TranscodedFileEntity transcodedFile = new TranscodedFileEntity(
                transcodedId, video, "1080p", "transcoded/video-1080p.mp4", 1024L
        );

        when(valueOperations.get("video:metadata:" + videoId)).thenReturn(null);
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));
        when(transcodedFileRepository.findByVideoId(videoId)).thenReturn(List.of(transcodedFile));

        Optional<VideoMetadata> result = metadataService.getVideoMetadata(videoId, "http://localhost:8080");

        assertThat(result).isPresent();
        assertThat(result.get().transcodedFiles()).hasSize(1);

        // Verify URL contains resolution
        String url = result.get().transcodedFiles().get(0).url();
        assertThat(url).contains("1080p");

        verify(valueOperations).set(eq("video:metadata:" + videoId), anyString(), any());
    }

    @Test
    void getVideoMetadata_notFound() {
        UUID videoId = UUID.randomUUID();

        when(valueOperations.get("video:metadata:" + videoId)).thenReturn(null);
        when(videoRepository.findById(videoId)).thenReturn(Optional.empty());

        Optional<VideoMetadata> result = metadataService.getVideoMetadata(videoId, "http://localhost:8080");

        assertThat(result).isEmpty();
    }
}
