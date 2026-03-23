package com.videosharing.apiserver;

import com.videosharing.apiserver.entity.TranscodedFileEntity;
import com.videosharing.apiserver.entity.VideoEntity;
import com.videosharing.apiserver.repository.TranscodedFileRepository;
import com.videosharing.apiserver.repository.VideoRepository;
import com.videosharing.apiserver.service.VideoStreamingService;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VideoStreamingServiceTest {

    @Mock
    private MinioClient minioClient;

    @Mock
    private VideoRepository videoRepository;

    @Mock
    private TranscodedFileRepository transcodedFileRepository;

    private VideoStreamingService streamingService;

    @BeforeEach
    void setUp() {
        streamingService = new VideoStreamingService(
                minioClient,
                videoRepository,
                transcodedFileRepository,
                "transcoded-storage"
        );
    }

    @Test
    void streamVideo_returnsStream() throws Exception {
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, "video.mp4", "originals/" + videoId + "/video.mp4");

        UUID transcodedId = UUID.randomUUID();
        TranscodedFileEntity transcodedFile = new TranscodedFileEntity(
                transcodedId, video, "1080p", "transcoded/video-1080p.mp4", 2048L
        );

        when(videoRepository.findByOriginalFilename("video.mp4")).thenReturn(Optional.of(video));
        when(transcodedFileRepository.findByVideoId(videoId)).thenReturn(List.of(transcodedFile));

        StatObjectResponse statResponse = mock(StatObjectResponse.class);
        when(statResponse.size()).thenReturn(2048L);
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(statResponse);

        GetObjectResponse getObjectResponse = new GetObjectResponse(
                Headers.of(),
                "transcoded-storage",
                null,
                "transcoded/video-1080p.mp4",
                new ByteArrayInputStream("video data".getBytes())
        );
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(getObjectResponse);

        Optional<VideoStreamingService.StreamResult> result = streamingService.streamVideo("video.mp4", "1080p");

        assertThat(result).isPresent();
        assertThat(result.get().contentLength()).isEqualTo(2048L);
        assertThat(result.get().contentType()).isEqualTo("video/mp4");
        assertThat(result.get().resource()).isNotNull();
    }

    @Test
    void streamVideo_fileNotFound_returnsEmpty() {
        when(videoRepository.findByOriginalFilename("missing.mp4")).thenReturn(Optional.empty());

        Optional<VideoStreamingService.StreamResult> result = streamingService.streamVideo("missing.mp4", "1080p");

        assertThat(result).isEmpty();
    }
}
