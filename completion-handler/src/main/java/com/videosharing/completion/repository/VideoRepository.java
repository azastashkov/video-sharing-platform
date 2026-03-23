package com.videosharing.completion.repository;

import com.videosharing.completion.entity.VideoEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface VideoRepository extends JpaRepository<VideoEntity, UUID> {
}
