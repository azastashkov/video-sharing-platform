package com.videosharing.apiserver;

import com.videosharing.apiserver.entity.VideoEntity;
import com.videosharing.apiserver.repository.VideoRepository;
import com.videosharing.apiserver.service.VideoUploadService;
import com.videosharing.common.dto.TranscodingTask;
import com.videosharing.common.dto.VideoStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VideoUploadServiceTest {

    @Mock
    private MinioClient minioClient;

    @Mock
    private VideoRepository videoRepository;

    @Mock
    private RabbitTemplate rabbitTemplate;

    @Mock
    private MultipartFile multipartFile;

    private VideoUploadService uploadService;

    @BeforeEach
    void setUp() {
        uploadService = new VideoUploadService(
                minioClient,
                videoRepository,
                rabbitTemplate,
                "original-storage",
                new SimpleMeterRegistry()
        );
    }

    @Test
    void uploadVideo_savesToMinioAndPublishesTask() throws Exception {
        String filename = "test-video.mp4";
        byte[] content = "video content".getBytes();

        when(multipartFile.getOriginalFilename()).thenReturn(filename);
        when(multipartFile.getSize()).thenReturn((long) content.length);
        when(multipartFile.getContentType()).thenReturn("video/mp4");
        when(multipartFile.getInputStream()).thenReturn(new ByteArrayInputStream(content));

        // Capture the status at the time of each save call
        List<VideoStatus> capturedStatuses = new ArrayList<>();
        when(videoRepository.save(any(VideoEntity.class))).thenAnswer(inv -> {
            VideoEntity entity = inv.getArgument(0);
            capturedStatuses.add(entity.getStatus());
            return entity;
        });

        UUID videoId = uploadService.uploadVideo(multipartFile);

        assertThat(videoId).isNotNull();

        // Verify MinIO putObject was called
        verify(minioClient).putObject(any(PutObjectArgs.class));

        // Verify DB saved twice (UPLOADING then TRANSCODING)
        assertThat(capturedStatuses).hasSize(2);
        assertThat(capturedStatuses.get(0)).isEqualTo(VideoStatus.UPLOADING);
        assertThat(capturedStatuses.get(1)).isEqualTo(VideoStatus.TRANSCODING);

        // Verify RabbitMQ message published with correct videoId and filename
        ArgumentCaptor<TranscodingTask> taskCaptor = ArgumentCaptor.forClass(TranscodingTask.class);
        verify(rabbitTemplate).convertAndSend(eq("transcoding-tasks"), taskCaptor.capture());
        TranscodingTask task = taskCaptor.getValue();
        assertThat(task.videoId()).isEqualTo(videoId);
        assertThat(task.originalFilename()).isEqualTo(filename);
    }
}
