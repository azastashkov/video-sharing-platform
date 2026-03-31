package com.videosharing.transcoding.service;

import com.videosharing.common.dto.TranscodedFileInfo;
import com.videosharing.common.dto.TranscodingCompletion;
import com.videosharing.common.dto.TranscodingTask;
import com.videosharing.transcoding.config.RabbitMQConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.ObjectWriteResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class TranscodingServiceTest {

    @Mock private MinioClient minioClient;
    @Mock private FFmpegService ffmpegService;
    @Mock private RabbitTemplate rabbitTemplate;

    private TranscodingService transcodingService;

    @BeforeEach
    void setUp() {
        transcodingService = new TranscodingService(
                minioClient, ffmpegService, rabbitTemplate,
                "original-storage", "transcoded-storage",
                new SimpleMeterRegistry());
    }

    @Test
    void processTranscodingTask_callsFFmpegForEachResolution(@TempDir Path tempDir) throws Exception {
        UUID videoId = UUID.randomUUID();
        TranscodingTask task = new TranscodingTask(videoId, "test.mp4", "originals/" + videoId + "/test.mp4");

        GetObjectResponse getResponse = mock(GetObjectResponse.class);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(getResponse);
        when(minioClient.putObject(any(PutObjectArgs.class))).thenReturn(mock(ObjectWriteResponse.class));

        // Mock FFmpeg to create dummy output files
        doAnswer(invocation -> {
            Path outputFile = invocation.getArgument(1);
            Files.write(outputFile, new byte[50]);
            return null;
        }).when(ffmpegService).transcode(any(), any(), any());

        transcodingService.processTranscodingTask(task);

        // Verify FFmpeg called for each resolution
        verify(ffmpegService).transcode(any(), any(), eq("144p"));
        verify(ffmpegService).transcode(any(), any(), eq("360p"));
        verify(ffmpegService).transcode(any(), any(), eq("720p"));

        // Verify completion published
        verify(rabbitTemplate).convertAndSend(eq(RabbitMQConfig.TRANSCODING_COMPLETIONS_QUEUE), any(TranscodingCompletion.class));

        // Verify 3 uploads to MinIO (one per resolution)
        verify(minioClient, times(3)).putObject(any(PutObjectArgs.class));
    }
}
