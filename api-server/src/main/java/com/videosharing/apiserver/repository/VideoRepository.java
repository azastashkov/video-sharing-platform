package com.videosharing.apiserver.repository;

import com.videosharing.apiserver.entity.VideoEntity;
import com.videosharing.common.dto.VideoStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface VideoRepository extends JpaRepository<VideoEntity, UUID> {
    Optional<VideoEntity> findFirstByOriginalFilenameAndStatusOrderByCreatedAtDesc(
            String originalFilename, VideoStatus status);
}
