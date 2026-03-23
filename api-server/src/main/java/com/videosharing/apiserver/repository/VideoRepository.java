package com.videosharing.apiserver.repository;

import com.videosharing.apiserver.entity.VideoEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.Optional;
import java.util.UUID;

public interface VideoRepository extends JpaRepository<VideoEntity, UUID> {
    Optional<VideoEntity> findByOriginalFilename(String originalFilename);
}
