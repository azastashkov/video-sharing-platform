package com.videosharing.transcoding.service;

import com.videosharing.common.dto.TranscodedFileInfo;
import com.videosharing.common.dto.TranscodingCompletion;
import com.videosharing.common.dto.TranscodingTask;
import com.videosharing.transcoding.config.RabbitMQConfig;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.minio.GetObjectArgs;
import io.minio.PutObjectArgs;
import io.minio.MinioClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

@Service
public class TranscodingService {

    private static final Logger log = LoggerFactory.getLogger(TranscodingService.class);
    private static final List<String> RESOLUTIONS = List.of("144p", "360p", "720p");

    private final MinioClient minioClient;
    private final FFmpegService ffmpegService;
    private final RabbitTemplate rabbitTemplate;
    private final String originalBucket;
    private final String transcodedBucket;
    private final MeterRegistry meterRegistry;
    private final AtomicInteger activeJobs = new AtomicInteger(0);

    public TranscodingService(MinioClient minioClient,
                              FFmpegService ffmpegService,
                              RabbitTemplate rabbitTemplate,
                              @Value("${minio.original-bucket}") String originalBucket,
                              @Value("${minio.transcoded-bucket}") String transcodedBucket,
                              MeterRegistry meterRegistry) {
        this.minioClient = minioClient;
        this.ffmpegService = ffmpegService;
        this.rabbitTemplate = rabbitTemplate;
        this.originalBucket = originalBucket;
        this.transcodedBucket = transcodedBucket;
        this.meterRegistry = meterRegistry;
        Gauge.builder("transcoding_jobs_active", activeJobs, AtomicInteger::get)
                .description("Currently active transcoding jobs")
                .register(meterRegistry);
    }

    public void processTranscodingTask(TranscodingTask task) throws Exception {
        activeJobs.incrementAndGet();
        try {
            doTranscode(task);
        } finally {
            activeJobs.decrementAndGet();
        }
    }

    private void doTranscode(TranscodingTask task) throws Exception {
        Path tempDir = Files.createTempDirectory("transcode-" + task.videoId());
        try {
            // Download original from MinIO
            Path inputFile = tempDir.resolve(task.originalFilename());
            try (InputStream is = minioClient.getObject(GetObjectArgs.builder()
                    .bucket(originalBucket)
                    .object(task.originalMinioKey())
                    .build())) {
                Files.copy(is, inputFile);
            }

            log.info("Downloaded original: videoId={}, file={}", task.videoId(), task.originalFilename());

            // Transcode to each resolution
            List<TranscodedFileInfo> transcodedFiles = new ArrayList<>();
            for (String resolution : RESOLUTIONS) {
                Path outputFile = tempDir.resolve(resolution + "-" + task.originalFilename());
                Timer.Sample sample = Timer.start(meterRegistry);
                ffmpegService.transcode(inputFile, outputFile, resolution);
                sample.stop(Timer.builder("transcoding_duration_seconds")
                        .description("Transcoding duration per resolution")
                        .tag("resolution", resolution)
                        .register(meterRegistry));

                // Upload to MinIO
                String minioKey = task.videoId() + "/" + resolution + "/" + task.originalFilename();
                long fileSize = Files.size(outputFile);

                try (InputStream is = Files.newInputStream(outputFile)) {
                    minioClient.putObject(PutObjectArgs.builder()
                            .bucket(transcodedBucket)
                            .object(minioKey)
                            .stream(is, fileSize, -1)
                            .contentType("video/mp4")
                            .build());
                }

                transcodedFiles.add(new TranscodedFileInfo(resolution, minioKey, fileSize));
                log.info("Transcoded and uploaded: videoId={}, resolution={}", task.videoId(), resolution);
            }

            // Publish completion
            TranscodingCompletion completion = new TranscodingCompletion(
                    task.videoId(), task.originalFilename(), transcodedFiles);
            rabbitTemplate.convertAndSend(RabbitMQConfig.TRANSCODING_COMPLETIONS_QUEUE, completion);
            log.info("Transcoding complete: videoId={}", task.videoId());

        } finally {
            // Clean up temp files
            try (var files = Files.walk(tempDir)) {
                files.sorted(java.util.Comparator.reverseOrder())
                     .forEach(p -> { try { Files.deleteIfExists(p); } catch (Exception ignored) {} });
            }
        }
    }
}
