package com.videosharing.completion.repository;

import com.videosharing.completion.entity.TranscodedFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface TranscodedFileRepository extends JpaRepository<TranscodedFileEntity, UUID> {
}
