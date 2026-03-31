package com.videosharing.completion.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "transcoded_files")
public class TranscodedFileEntity {

    @Id
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "video_id", nullable = false)
    private VideoEntity video;

    @Column(nullable = false, length = 10)
    private String resolution;

    @Column(name = "minio_key", nullable = false, length = 500)
    private String minioKey;

    @Column(name = "file_size")
    private Long fileSize;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    public TranscodedFileEntity() {}

    public TranscodedFileEntity(UUID id, VideoEntity video, String resolution, String minioKey, Long fileSize) {
        this.id = id;
        this.video = video;
        this.resolution = resolution;
        this.minioKey = minioKey;
        this.fileSize = fileSize;
    }
}
