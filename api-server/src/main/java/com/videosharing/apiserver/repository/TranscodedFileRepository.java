package com.videosharing.apiserver.repository;

import com.videosharing.apiserver.entity.TranscodedFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface TranscodedFileRepository extends JpaRepository<TranscodedFileEntity, UUID> {
    List<TranscodedFileEntity> findByVideoId(UUID videoId);
}
