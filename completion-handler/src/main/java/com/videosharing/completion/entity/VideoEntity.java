package com.videosharing.completion.entity;

import com.videosharing.common.dto.VideoStatus;
import jakarta.persistence.*;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "videos")
public class VideoEntity {

    @Id
    private UUID id;

    @Column(name = "original_filename", nullable = false)
    private String originalFilename;

    @Column(name = "original_minio_key", nullable = false, length = 500)
    private String originalMinioKey;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 50)
    private VideoStatus status = VideoStatus.UPLOADING;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @OneToMany(mappedBy = "video", cascade = CascadeType.ALL, fetch = FetchType.LAZY)
    private List<TranscodedFileEntity> transcodedFiles = new ArrayList<>();

    public VideoEntity() {}

    public VideoEntity(UUID id, String originalFilename, String originalMinioKey) {
        this.id = id;
        this.originalFilename = originalFilename;
        this.originalMinioKey = originalMinioKey;
    }

    public UUID getId() { return id; }
    public String getOriginalFilename() { return originalFilename; }
    public String getOriginalMinioKey() { return originalMinioKey; }
    public VideoStatus getStatus() { return status; }
    public void setStatus(VideoStatus status) { this.status = status; this.updatedAt = Instant.now(); }
    public List<TranscodedFileEntity> getTranscodedFiles() { return transcodedFiles; }

    @PreUpdate
    protected void onUpdate() { this.updatedAt = Instant.now(); }
}
