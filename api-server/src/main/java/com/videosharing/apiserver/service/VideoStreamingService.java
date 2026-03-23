package com.videosharing.apiserver.service;

import com.videosharing.apiserver.entity.TranscodedFileEntity;
import com.videosharing.apiserver.entity.VideoEntity;
import com.videosharing.apiserver.repository.TranscodedFileRepository;
import com.videosharing.apiserver.repository.VideoRepository;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.List;
import java.util.Optional;

@Service
public class VideoStreamingService {

    public record StreamResult(InputStreamResource resource, long contentLength, String contentType) {}

    private final MinioClient minioClient;
    private final VideoRepository videoRepository;
    private final TranscodedFileRepository transcodedFileRepository;
    private final String transcodedBucket;

    public VideoStreamingService(
            MinioClient minioClient,
            VideoRepository videoRepository,
            TranscodedFileRepository transcodedFileRepository,
            @Value("${minio.transcoded-bucket}") String transcodedBucket) {
        this.minioClient = minioClient;
        this.videoRepository = videoRepository;
        this.transcodedFileRepository = transcodedFileRepository;
        this.transcodedBucket = transcodedBucket;
    }

    public Optional<StreamResult> streamVideo(String filename, String resolution) {
        Optional<VideoEntity> videoOpt = videoRepository.findByOriginalFilename(filename);
        if (videoOpt.isEmpty()) {
            return Optional.empty();
        }

        VideoEntity video = videoOpt.get();
        List<TranscodedFileEntity> transcodedFiles = transcodedFileRepository.findByVideoId(video.getId());

        Optional<TranscodedFileEntity> transcodedFile = transcodedFiles.stream()
                .filter(tf -> tf.getResolution().equals(resolution))
                .findFirst();

        if (transcodedFile.isEmpty()) {
            return Optional.empty();
        }

        TranscodedFileEntity tf = transcodedFile.get();

        try {
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder()
                            .bucket(transcodedBucket)
                            .object(tf.getMinioKey())
                            .build()
            );

            InputStream inputStream = minioClient.getObject(
                    GetObjectArgs.builder()
                            .bucket(transcodedBucket)
                            .object(tf.getMinioKey())
                            .build()
            );

            return Optional.of(new StreamResult(
                    new InputStreamResource(inputStream),
                    stat.size(),
                    "video/mp4"
            ));
        } catch (Exception e) {
            return Optional.empty();
        }
    }
}
