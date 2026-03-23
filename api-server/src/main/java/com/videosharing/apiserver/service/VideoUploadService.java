package com.videosharing.apiserver.service;

import com.videosharing.apiserver.entity.VideoEntity;
import com.videosharing.apiserver.repository.VideoRepository;
import com.videosharing.common.dto.TranscodingTask;
import com.videosharing.common.dto.VideoStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;

@Service
public class VideoUploadService {

    private final MinioClient minioClient;
    private final VideoRepository videoRepository;
    private final RabbitTemplate rabbitTemplate;
    private final String originalBucket;
    private final Counter uploadCounter;
    private final DistributionSummary uploadSizeSummary;

    public VideoUploadService(
            MinioClient minioClient,
            VideoRepository videoRepository,
            RabbitTemplate rabbitTemplate,
            @Value("${minio.original-bucket}") String originalBucket,
            MeterRegistry meterRegistry) {
        this.minioClient = minioClient;
        this.videoRepository = videoRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.originalBucket = originalBucket;
        this.uploadCounter = Counter.builder("video_upload_total")
                .description("Total number of video uploads")
                .register(meterRegistry);
        this.uploadSizeSummary = DistributionSummary.builder("video_upload_size_bytes")
                .description("Distribution of video upload sizes in bytes")
                .register(meterRegistry);
    }

    public UUID uploadVideo(MultipartFile file) throws Exception {
        UUID videoId = UUID.randomUUID();
        String filename = file.getOriginalFilename();
        String minioKey = "originals/" + videoId + "/" + filename;

        // Upload to MinIO
        try (InputStream inputStream = file.getInputStream()) {
            minioClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(originalBucket)
                            .object(minioKey)
                            .stream(inputStream, file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build()
            );
        }

        // Save entity with UPLOADING status
        VideoEntity entity = new VideoEntity(videoId, filename, minioKey);
        entity.setStatus(VideoStatus.UPLOADING);
        videoRepository.save(entity);

        // Update to TRANSCODING status
        entity.setStatus(VideoStatus.TRANSCODING);
        videoRepository.save(entity);

        // Publish transcoding task
        TranscodingTask task = new TranscodingTask(videoId, filename, minioKey);
        rabbitTemplate.convertAndSend("transcoding-tasks", task);

        // Record metrics
        uploadCounter.increment();
        uploadSizeSummary.record(file.getSize());

        return videoId;
    }
}
