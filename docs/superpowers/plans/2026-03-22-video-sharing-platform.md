# Video Sharing Platform Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a scalable video sharing platform with upload, transcoding to 144p/360p/720p, and streaming — using microservices behind Nginx with full monitoring.

**Architecture:** Three Spring Boot microservices (api-server, transcoding-worker, completion-handler) each running 2 replicas behind Nginx. MinIO for object storage, RabbitMQ for async messaging, PostgreSQL for metadata, Redis for caching. Prometheus + Grafana for monitoring. Java load test client.

**Tech Stack:** Java 21, Spring Boot 3.3.x, Groovy Gradle 8.x, Spring AMQP, Spring Data JPA, Spring Data Redis, MinIO SDK, FFmpeg, Micrometer, Nginx, Docker Compose

**Spec:** `docs/superpowers/specs/2026-03-22-video-sharing-platform-design.md`

---

### Task 1: Root Gradle Build & Common Module

**Files:**
- Create: `settings.gradle`
- Create: `build.gradle`
- Create: `common/build.gradle`
- Create: `common/src/main/java/com/videosharing/common/dto/TranscodingTask.java`
- Create: `common/src/main/java/com/videosharing/common/dto/TranscodingCompletion.java`
- Create: `common/src/main/java/com/videosharing/common/dto/TranscodedFileInfo.java`
- Create: `common/src/main/java/com/videosharing/common/dto/VideoMetadata.java`
- Create: `common/src/main/java/com/videosharing/common/dto/VideoStatus.java`

- [ ] **Step 1: Create `settings.gradle`**

```groovy
rootProject.name = 'video-sharing-platform'

include 'common'
include 'api-server'
include 'transcoding-worker'
include 'completion-handler'
include 'load-client'
```

- [ ] **Step 2: Create root `build.gradle`**

```groovy
plugins {
    id 'java'
    id 'org.springframework.boot' version '3.3.5' apply false
    id 'io.spring.dependency-management' version '1.1.6' apply false
}

allprojects {
    group = 'com.videosharing'
    version = '1.0.0'

    repositories {
        mavenCentral()
    }
}

subprojects {
    apply plugin: 'java'

    java {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    tasks.withType(Test) {
        useJUnitPlatform()
    }
}
```

- [ ] **Step 3: Create `common/build.gradle`**

```groovy
plugins {
    id 'java-library'
}

dependencies {
    api 'com.fasterxml.jackson.core:jackson-databind:2.17.2'
}
```

- [ ] **Step 4: Create `VideoStatus.java` enum**

```java
package com.videosharing.common.dto;

public enum VideoStatus {
    UPLOADING,
    TRANSCODING,
    COMPLETED,
    FAILED
}
```

- [ ] **Step 5: Create `TranscodingTask.java`**

```java
package com.videosharing.common.dto;

import java.util.UUID;

public record TranscodingTask(
        UUID videoId,
        String originalFilename,
        String originalMinioKey
) {}
```

- [ ] **Step 6: Create `TranscodedFileInfo.java`**

```java
package com.videosharing.common.dto;

public record TranscodedFileInfo(
        String resolution,
        String minioKey,
        long fileSize
) {}
```

- [ ] **Step 7: Create `TranscodingCompletion.java`**

```java
package com.videosharing.common.dto;

import java.util.List;
import java.util.UUID;

public record TranscodingCompletion(
        UUID videoId,
        String originalFilename,
        List<TranscodedFileInfo> transcodedFiles
) {}
```

- [ ] **Step 8: Create `VideoMetadata.java`**

```java
package com.videosharing.common.dto;

import java.io.Serializable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record VideoMetadata(
        UUID id,
        String originalFilename,
        VideoStatus status,
        List<TranscodedFileReference> transcodedFiles,
        Instant createdAt
) implements Serializable {

    public record TranscodedFileReference(
            String resolution,
            String url,
            long fileSize
    ) implements Serializable {}
}
```

- [ ] **Step 9: Verify build compiles**

Run: `./gradlew :common:build`
Expected: BUILD SUCCESSFUL

- [ ] **Step 10: Commit**

```bash
git add settings.gradle build.gradle common/
git commit -m "feat: add root Gradle build and common DTO module"
```

---

### Task 2: API Server — JPA Entities, Repositories & Application Shell

**Files:**
- Create: `api-server/build.gradle`
- Create: `api-server/src/main/java/com/videosharing/apiserver/ApiServerApplication.java`
- Create: `api-server/src/main/java/com/videosharing/apiserver/entity/VideoEntity.java`
- Create: `api-server/src/main/java/com/videosharing/apiserver/entity/TranscodedFileEntity.java`
- Create: `api-server/src/main/java/com/videosharing/apiserver/repository/VideoRepository.java`
- Create: `api-server/src/main/java/com/videosharing/apiserver/repository/TranscodedFileRepository.java`
- Create: `api-server/src/main/resources/application.yml`
- Create: `api-server/src/main/resources/schema.sql`

- [ ] **Step 1: Create `api-server/build.gradle`**

```groovy
plugins {
    id 'org.springframework.boot'
    id 'io.spring.dependency-management'
}

dependencies {
    implementation project(':common')

    implementation 'org.springframework.boot:spring-boot-starter-web'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
    implementation 'org.springframework.boot:spring-boot-starter-amqp'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'io.micrometer:micrometer-registry-prometheus'
    implementation 'io.minio:minio:8.5.12'

    runtimeOnly 'org.postgresql:postgresql'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
}
```

- [ ] **Step 2: Create `ApiServerApplication.java`**

```java
package com.videosharing.apiserver;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class ApiServerApplication {
    public static void main(String[] args) {
        SpringApplication.run(ApiServerApplication.class, args);
    }
}
```

- [ ] **Step 3: Create `VideoEntity.java`**

```java
package com.videosharing.apiserver.entity;

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

    // Getters and setters
    public UUID getId() { return id; }
    public void setId(UUID id) { this.id = id; }
    public String getOriginalFilename() { return originalFilename; }
    public void setOriginalFilename(String originalFilename) { this.originalFilename = originalFilename; }
    public String getOriginalMinioKey() { return originalMinioKey; }
    public void setOriginalMinioKey(String originalMinioKey) { this.originalMinioKey = originalMinioKey; }
    public VideoStatus getStatus() { return status; }
    public void setStatus(VideoStatus status) { this.status = status; this.updatedAt = Instant.now(); }
    public Instant getCreatedAt() { return createdAt; }
    public Instant getUpdatedAt() { return updatedAt; }
    public List<TranscodedFileEntity> getTranscodedFiles() { return transcodedFiles; }
    public void setTranscodedFiles(List<TranscodedFileEntity> transcodedFiles) { this.transcodedFiles = transcodedFiles; }

    @PreUpdate
    protected void onUpdate() { this.updatedAt = Instant.now(); }
}
```

- [ ] **Step 4: Create `TranscodedFileEntity.java`**

```java
package com.videosharing.apiserver.entity;

import jakarta.persistence.*;
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

    // Getters
    public UUID getId() { return id; }
    public VideoEntity getVideo() { return video; }
    public String getResolution() { return resolution; }
    public String getMinioKey() { return minioKey; }
    public Long getFileSize() { return fileSize; }
    public Instant getCreatedAt() { return createdAt; }
}
```

- [ ] **Step 5: Create `VideoRepository.java`**

```java
package com.videosharing.apiserver.repository;

import com.videosharing.apiserver.entity.VideoEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface VideoRepository extends JpaRepository<VideoEntity, UUID> {
    Optional<VideoEntity> findByOriginalFilename(String originalFilename);
}
```

- [ ] **Step 6: Create `TranscodedFileRepository.java`**

```java
package com.videosharing.apiserver.repository;

import com.videosharing.apiserver.entity.TranscodedFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List;
import java.util.UUID;

public interface TranscodedFileRepository extends JpaRepository<TranscodedFileEntity, UUID> {
    List<TranscodedFileEntity> findByVideoId(UUID videoId);
}
```

- [ ] **Step 7: Create `application.yml`**

```yaml
server:
  port: 8080

spring:
  application:
    name: api-server
  servlet:
    multipart:
      max-file-size: 500MB
      max-request-size: 500MB
  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST:localhost}:5432/${POSTGRES_DB:videodb}
    username: ${POSTGRES_USER:videouser}
    password: ${POSTGRES_PASSWORD:videopass}
  jpa:
    hibernate:
      ddl-auto: none
    open-in-view: false
  sql:
    init:
      mode: always
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: 5672
    username: ${RABBITMQ_USER:guest}
    password: ${RABBITMQ_PASSWORD:guest}
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: 6379

minio:
  endpoint: http://${MINIO_HOST:localhost}:9000
  access-key: ${MINIO_ACCESS_KEY:minioadmin}
  secret-key: ${MINIO_SECRET_KEY:minioadmin}
  original-bucket: original-storage
  transcoded-bucket: transcoded-storage

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info
  metrics:
    tags:
      application: api-server
```

- [ ] **Step 8: Create `schema.sql`**

```sql
CREATE TABLE IF NOT EXISTS videos (
    id UUID PRIMARY KEY,
    original_filename VARCHAR(255) NOT NULL,
    original_minio_key VARCHAR(500) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'UPLOADING',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE IF NOT EXISTS transcoded_files (
    id UUID PRIMARY KEY,
    video_id UUID NOT NULL REFERENCES videos(id),
    resolution VARCHAR(10) NOT NULL,
    minio_key VARCHAR(500) NOT NULL,
    file_size BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_transcoded_files_video_id ON transcoded_files(video_id);
```

- [ ] **Step 9: Verify build compiles**

Run: `./gradlew :api-server:build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 10: Commit**

```bash
git add api-server/
git commit -m "feat: add api-server shell with JPA entities and repositories"
```

---

### Task 3: API Server — Services (Upload, Metadata, Streaming)

**Files:**
- Create: `api-server/src/main/java/com/videosharing/apiserver/config/MinioClientConfig.java`
- Create: `api-server/src/main/java/com/videosharing/apiserver/config/RabbitMQConfig.java`
- Create: `api-server/src/main/java/com/videosharing/apiserver/service/VideoUploadService.java`
- Create: `api-server/src/main/java/com/videosharing/apiserver/service/VideoMetadataService.java`
- Create: `api-server/src/main/java/com/videosharing/apiserver/service/VideoStreamingService.java`

- [ ] **Step 1: Create `MinioClientConfig.java`**

```java
package com.videosharing.apiserver.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioClientConfig {

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.access-key}")
    private String accessKey;

    @Value("${minio.secret-key}")
    private String secretKey;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
```

- [ ] **Step 2: Create `RabbitMQConfig.java`**

```java
package com.videosharing.apiserver.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String TRANSCODING_TASKS_QUEUE = "transcoding-tasks";
    public static final String TRANSCODING_TASKS_DLQ = "transcoding-tasks-dlq";
    public static final String TRANSCODING_COMPLETIONS_QUEUE = "transcoding-completions";
    public static final String TRANSCODING_COMPLETIONS_DLQ = "transcoding-completions-dlq";
    public static final String DLX_EXCHANGE = "dlx-exchange";

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(DLX_EXCHANGE);
    }

    @Bean
    public Queue transcodingTasksQueue() {
        return QueueBuilder.durable(TRANSCODING_TASKS_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", TRANSCODING_TASKS_DLQ)
                .build();
    }

    @Bean
    public Queue transcodingTasksDlq() {
        return QueueBuilder.durable(TRANSCODING_TASKS_DLQ).build();
    }

    @Bean
    public Queue transcodingCompletionsQueue() {
        return QueueBuilder.durable(TRANSCODING_COMPLETIONS_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", TRANSCODING_COMPLETIONS_DLQ)
                .build();
    }

    @Bean
    public Queue transcodingCompletionsDlq() {
        return QueueBuilder.durable(TRANSCODING_COMPLETIONS_DLQ).build();
    }

    @Bean
    public Binding dlqTasksBinding() {
        return BindingBuilder.bind(transcodingTasksDlq())
                .to(dlxExchange())
                .with(TRANSCODING_TASKS_DLQ);
    }

    @Bean
    public Binding dlqCompletionsBinding() {
        return BindingBuilder.bind(transcodingCompletionsDlq())
                .to(dlxExchange())
                .with(TRANSCODING_COMPLETIONS_DLQ);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
```

- [ ] **Step 3: Create `VideoUploadService.java`**

```java
package com.videosharing.apiserver.service;

import com.videosharing.apiserver.config.RabbitMQConfig;
import com.videosharing.apiserver.entity.VideoEntity;
import com.videosharing.apiserver.repository.VideoRepository;
import com.videosharing.common.dto.TranscodingTask;
import com.videosharing.common.dto.VideoStatus;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.DistributionSummary;
import io.micrometer.core.instrument.MeterRegistry;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.io.InputStream;
import java.util.UUID;

@Service
public class VideoUploadService {

    private static final Logger log = LoggerFactory.getLogger(VideoUploadService.class);

    private final MinioClient minioClient;
    private final VideoRepository videoRepository;
    private final RabbitTemplate rabbitTemplate;
    private final String originalBucket;
    private final Counter uploadCounter;
    private final DistributionSummary uploadSizeSummary;

    public VideoUploadService(MinioClient minioClient,
                              VideoRepository videoRepository,
                              RabbitTemplate rabbitTemplate,
                              @Value("${minio.original-bucket}") String originalBucket,
                              MeterRegistry meterRegistry) {
        this.minioClient = minioClient;
        this.videoRepository = videoRepository;
        this.rabbitTemplate = rabbitTemplate;
        this.originalBucket = originalBucket;
        this.uploadCounter = Counter.builder("video_upload_total")
                .description("Total video uploads")
                .register(meterRegistry);
        this.uploadSizeSummary = DistributionSummary.builder("video_upload_size_bytes")
                .description("Upload file sizes")
                .register(meterRegistry);
    }

    public UUID uploadVideo(MultipartFile file) throws Exception {
        UUID videoId = UUID.randomUUID();
        String originalFilename = file.getOriginalFilename();
        String minioKey = "originals/" + videoId + "/" + originalFilename;

        // Upload to MinIO
        try (InputStream is = file.getInputStream()) {
            minioClient.putObject(PutObjectArgs.builder()
                    .bucket(originalBucket)
                    .object(minioKey)
                    .stream(is, file.getSize(), -1)
                    .contentType(file.getContentType())
                    .build());
        }

        // Save to DB
        VideoEntity video = new VideoEntity(videoId, originalFilename, minioKey);
        video.setStatus(VideoStatus.UPLOADING);
        videoRepository.save(video);

        // Publish transcoding task
        TranscodingTask task = new TranscodingTask(videoId, originalFilename, minioKey);
        rabbitTemplate.convertAndSend(RabbitMQConfig.TRANSCODING_TASKS_QUEUE, task);

        // Update status to TRANSCODING
        video.setStatus(VideoStatus.TRANSCODING);
        videoRepository.save(video);

        uploadCounter.increment();
        uploadSizeSummary.record(file.getSize());

        log.info("Video uploaded: id={}, filename={}, size={}", videoId, originalFilename, file.getSize());
        return videoId;
    }
}
```

- [ ] **Step 4: Create `VideoMetadataService.java`**

```java
package com.videosharing.apiserver.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.videosharing.apiserver.entity.TranscodedFileEntity;
import com.videosharing.apiserver.entity.VideoEntity;
import com.videosharing.apiserver.repository.TranscodedFileRepository;
import com.videosharing.apiserver.repository.VideoRepository;
import com.videosharing.common.dto.VideoMetadata;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

@Service
public class VideoMetadataService {

    private static final Logger log = LoggerFactory.getLogger(VideoMetadataService.class);
    private static final String CACHE_PREFIX = "video:metadata:";
    private static final Duration CACHE_TTL = Duration.ofMinutes(5);

    private final VideoRepository videoRepository;
    private final TranscodedFileRepository transcodedFileRepository;
    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final String baseUrl;

    public VideoMetadataService(VideoRepository videoRepository,
                                TranscodedFileRepository transcodedFileRepository,
                                StringRedisTemplate redisTemplate,
                                ObjectMapper objectMapper) {
        this.videoRepository = videoRepository;
        this.transcodedFileRepository = transcodedFileRepository;
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.baseUrl = "";  // Will be resolved from request
    }

    public Optional<VideoMetadata> getVideoMetadata(UUID videoId, String requestBaseUrl) {
        // Try cache first
        try {
            String cached = redisTemplate.opsForValue().get(CACHE_PREFIX + videoId);
            if (cached != null) {
                log.debug("Cache hit for video {}", videoId);
                return Optional.of(objectMapper.readValue(cached, VideoMetadata.class));
            }
        } catch (Exception e) {
            log.warn("Redis read failed, falling through to DB: {}", e.getMessage());
        }

        // Cache miss — read from DB
        return videoRepository.findById(videoId).map(video -> {
            List<TranscodedFileEntity> files = transcodedFileRepository.findByVideoId(videoId);
            VideoMetadata metadata = toMetadata(video, files, requestBaseUrl);

            // Cache the result
            try {
                String json = objectMapper.writeValueAsString(metadata);
                redisTemplate.opsForValue().set(CACHE_PREFIX + videoId, json, CACHE_TTL);
            } catch (JsonProcessingException | Exception e) {
                log.warn("Failed to cache metadata: {}", e.getMessage());
            }

            return metadata;
        });
    }

    public List<VideoMetadata> listAllVideos(String requestBaseUrl) {
        return videoRepository.findAll().stream()
                .map(video -> {
                    List<TranscodedFileEntity> files = transcodedFileRepository.findByVideoId(video.getId());
                    return toMetadata(video, files, requestBaseUrl);
                })
                .toList();
    }

    private VideoMetadata toMetadata(VideoEntity video, List<TranscodedFileEntity> files, String requestBaseUrl) {
        List<VideoMetadata.TranscodedFileReference> refs = files.stream()
                .map(f -> new VideoMetadata.TranscodedFileReference(
                        f.getResolution(),
                        requestBaseUrl + "/api/videos/stream?file=" + video.getOriginalFilename() + "&resolution=" + f.getResolution(),
                        f.getFileSize() != null ? f.getFileSize() : 0
                ))
                .toList();

        return new VideoMetadata(
                video.getId(),
                video.getOriginalFilename(),
                video.getStatus(),
                refs,
                video.getCreatedAt()
        );
    }
}
```

- [ ] **Step 5: Create `VideoStreamingService.java`**

```java
package com.videosharing.apiserver.service;

import com.videosharing.apiserver.entity.TranscodedFileEntity;
import com.videosharing.apiserver.entity.VideoEntity;
import com.videosharing.apiserver.repository.TranscodedFileRepository;
import com.videosharing.apiserver.repository.VideoRepository;
import io.minio.GetObjectArgs;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.InputStreamResource;
import org.springframework.stereotype.Service;

import java.io.InputStream;
import java.util.Optional;

@Service
public class VideoStreamingService {

    private static final Logger log = LoggerFactory.getLogger(VideoStreamingService.class);

    private final MinioClient minioClient;
    private final VideoRepository videoRepository;
    private final TranscodedFileRepository transcodedFileRepository;
    private final String transcodedBucket;

    public VideoStreamingService(MinioClient minioClient,
                                 VideoRepository videoRepository,
                                 TranscodedFileRepository transcodedFileRepository,
                                 @Value("${minio.transcoded-bucket}") String transcodedBucket) {
        this.minioClient = minioClient;
        this.videoRepository = videoRepository;
        this.transcodedFileRepository = transcodedFileRepository;
        this.transcodedBucket = transcodedBucket;
    }

    public record StreamResult(InputStreamResource resource, long contentLength, String contentType) {}

    public Optional<StreamResult> streamVideo(String filename, String resolution) {
        // Find the video by original filename
        Optional<VideoEntity> videoOpt = videoRepository.findByOriginalFilename(filename);

        if (videoOpt.isEmpty()) {
            return Optional.empty();
        }

        // Find the transcoded file for the requested resolution
        Optional<TranscodedFileEntity> transcodedOpt = transcodedFileRepository
                .findByVideoId(videoOpt.get().getId())
                .stream()
                .filter(f -> f.getResolution().equals(resolution))
                .findFirst();

        if (transcodedOpt.isEmpty()) {
            return Optional.empty();
        }

        try {
            String minioKey = transcodedOpt.get().getMinioKey();
            StatObjectResponse stat = minioClient.statObject(
                    StatObjectArgs.builder().bucket(transcodedBucket).object(minioKey).build());

            InputStream stream = minioClient.getObject(
                    GetObjectArgs.builder().bucket(transcodedBucket).object(minioKey).build());

            return Optional.of(new StreamResult(
                    new InputStreamResource(stream),
                    stat.size(),
                    "video/mp4"
            ));
        } catch (Exception e) {
            log.error("Failed to stream video: file={}, resolution={}", filename, resolution, e);
            return Optional.empty();
        }
    }
}
```

- [ ] **Step 6: Verify build compiles**

Run: `./gradlew :api-server:build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 7: Commit**

```bash
git add api-server/src/main/java/com/videosharing/apiserver/config/ api-server/src/main/java/com/videosharing/apiserver/service/
git commit -m "feat: add api-server services for upload, metadata, and streaming"
```

---

### Task 4: API Server — Controller & Unit Tests

**Files:**
- Create: `api-server/src/main/java/com/videosharing/apiserver/controller/VideoController.java`
- Create: `api-server/src/test/java/com/videosharing/apiserver/service/VideoUploadServiceTest.java`
- Create: `api-server/src/test/java/com/videosharing/apiserver/service/VideoMetadataServiceTest.java`
- Create: `api-server/src/test/java/com/videosharing/apiserver/service/VideoStreamingServiceTest.java`
- Create: `api-server/src/test/java/com/videosharing/apiserver/controller/VideoControllerTest.java`

- [ ] **Step 1: Create `VideoController.java`**

```java
package com.videosharing.apiserver.controller;

import com.videosharing.apiserver.service.VideoMetadataService;
import com.videosharing.apiserver.service.VideoStreamingService;
import com.videosharing.apiserver.service.VideoUploadService;
import com.videosharing.common.dto.VideoMetadata;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/videos")
public class VideoController {

    private final VideoUploadService uploadService;
    private final VideoMetadataService metadataService;
    private final VideoStreamingService streamingService;

    public VideoController(VideoUploadService uploadService,
                           VideoMetadataService metadataService,
                           VideoStreamingService streamingService) {
        this.uploadService = uploadService;
        this.metadataService = metadataService;
        this.streamingService = streamingService;
    }

    @PostMapping("/upload")
    public ResponseEntity<Map<String, String>> uploadVideo(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File is empty"));
        }
        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".mp4")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only mp4 files are accepted"));
        }

        try {
            UUID videoId = uploadService.uploadVideo(file);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Map.of("videoId", videoId.toString(), "status", "TRANSCODING"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Upload failed: " + e.getMessage()));
        }
    }

    @GetMapping("/{videoId}")
    public ResponseEntity<VideoMetadata> getVideoMetadata(@PathVariable UUID videoId,
                                                          HttpServletRequest request) {
        String baseUrl = request.getScheme() + "://" + request.getHeader("Host");
        return metadataService.getVideoMetadata(videoId, baseUrl)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<VideoMetadata>> listVideos(HttpServletRequest request) {
        String baseUrl = request.getScheme() + "://" + request.getHeader("Host");
        return ResponseEntity.ok(metadataService.listAllVideos(baseUrl));
    }

    @GetMapping("/stream")
    public ResponseEntity<?> streamVideo(@RequestParam("file") String file,
                                         @RequestParam("resolution") String resolution) {
        return streamingService.streamVideo(file, resolution)
                .map(result -> ResponseEntity.ok()
                        .header(HttpHeaders.CONTENT_TYPE, result.contentType())
                        .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(result.contentLength()))
                        .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + resolution + "-" + file + "\"")
                        .body(result.resource()))
                .orElse(ResponseEntity.notFound().build());
    }
}
```

- [ ] **Step 2: Write `VideoUploadServiceTest.java`**

```java
package com.videosharing.apiserver.service;

import com.videosharing.apiserver.config.RabbitMQConfig;
import com.videosharing.apiserver.entity.VideoEntity;
import com.videosharing.apiserver.repository.VideoRepository;
import com.videosharing.common.dto.TranscodingTask;
import com.videosharing.common.dto.VideoStatus;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.minio.MinioClient;
import io.minio.ObjectWriteResponse;
import io.minio.PutObjectArgs;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.web.multipart.MultipartFile;

import java.io.ByteArrayInputStream;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VideoUploadServiceTest {

    @Mock private MinioClient minioClient;
    @Mock private VideoRepository videoRepository;
    @Mock private RabbitTemplate rabbitTemplate;
    @Mock private MultipartFile multipartFile;

    private VideoUploadService uploadService;

    @BeforeEach
    void setUp() {
        uploadService = new VideoUploadService(
                minioClient, videoRepository, rabbitTemplate,
                "original-storage", new SimpleMeterRegistry());
    }

    @Test
    void uploadVideo_savesToMinioAndPublishesTask() throws Exception {
        when(multipartFile.getOriginalFilename()).thenReturn("test.mp4");
        when(multipartFile.getSize()).thenReturn(1024L);
        when(multipartFile.getContentType()).thenReturn("video/mp4");
        when(multipartFile.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[1024]));
        when(minioClient.putObject(any(PutObjectArgs.class))).thenReturn(mock(ObjectWriteResponse.class));
        when(videoRepository.save(any(VideoEntity.class))).thenAnswer(i -> i.getArgument(0));

        UUID videoId = uploadService.uploadVideo(multipartFile);

        assertNotNull(videoId);

        // Verify MinIO upload
        verify(minioClient).putObject(any(PutObjectArgs.class));

        // Verify DB save (called twice: once for UPLOADING, once for TRANSCODING)
        ArgumentCaptor<VideoEntity> videoCaptor = ArgumentCaptor.forClass(VideoEntity.class);
        verify(videoRepository, times(2)).save(videoCaptor.capture());
        VideoEntity savedVideo = videoCaptor.getAllValues().get(1);
        assertEquals(VideoStatus.TRANSCODING, savedVideo.getStatus());

        // Verify RabbitMQ publish
        ArgumentCaptor<TranscodingTask> taskCaptor = ArgumentCaptor.forClass(TranscodingTask.class);
        verify(rabbitTemplate).convertAndSend(eq(RabbitMQConfig.TRANSCODING_TASKS_QUEUE), taskCaptor.capture());
        TranscodingTask task = taskCaptor.getValue();
        assertEquals(videoId, task.videoId());
        assertEquals("test.mp4", task.originalFilename());
        assertTrue(task.originalMinioKey().contains(videoId.toString()));
    }
}
```

- [ ] **Step 3: Write `VideoMetadataServiceTest.java`**

```java
package com.videosharing.apiserver.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.videosharing.apiserver.entity.TranscodedFileEntity;
import com.videosharing.apiserver.entity.VideoEntity;
import com.videosharing.apiserver.repository.TranscodedFileRepository;
import com.videosharing.apiserver.repository.VideoRepository;
import com.videosharing.common.dto.VideoMetadata;
import com.videosharing.common.dto.VideoStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VideoMetadataServiceTest {

    @Mock private VideoRepository videoRepository;
    @Mock private TranscodedFileRepository transcodedFileRepository;
    @Mock private StringRedisTemplate redisTemplate;
    @Mock private ValueOperations<String, String> valueOps;

    private VideoMetadataService metadataService;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        objectMapper.registerModule(new JavaTimeModule());
        metadataService = new VideoMetadataService(
                videoRepository, transcodedFileRepository, redisTemplate, objectMapper);
    }

    @Test
    void getVideoMetadata_returnsCachedResult() throws Exception {
        UUID videoId = UUID.randomUUID();
        VideoMetadata metadata = new VideoMetadata(videoId, "test.mp4", VideoStatus.COMPLETED, List.of(), java.time.Instant.now());
        String json = objectMapper.writeValueAsString(metadata);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("video:metadata:" + videoId)).thenReturn(json);

        Optional<VideoMetadata> result = metadataService.getVideoMetadata(videoId, "http://localhost:8080");

        assertTrue(result.isPresent());
        assertEquals("test.mp4", result.get().originalFilename());
        verify(videoRepository, never()).findById(any());
    }

    @Test
    void getVideoMetadata_cacheMiss_readsFromDb() {
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, "test.mp4", "originals/" + videoId + "/test.mp4");
        video.setStatus(VideoStatus.COMPLETED);

        TranscodedFileEntity file720 = new TranscodedFileEntity(
                UUID.randomUUID(), video, "720p", videoId + "/720p/test.mp4", 5000L);

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));
        when(transcodedFileRepository.findByVideoId(videoId)).thenReturn(List.of(file720));

        Optional<VideoMetadata> result = metadataService.getVideoMetadata(videoId, "http://localhost:8080");

        assertTrue(result.isPresent());
        assertEquals(1, result.get().transcodedFiles().size());
        assertEquals("720p", result.get().transcodedFiles().get(0).resolution());
        assertTrue(result.get().transcodedFiles().get(0).url().contains("resolution=720p"));
    }

    @Test
    void getVideoMetadata_notFound() {
        UUID videoId = UUID.randomUUID();
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(anyString())).thenReturn(null);
        when(videoRepository.findById(videoId)).thenReturn(Optional.empty());

        Optional<VideoMetadata> result = metadataService.getVideoMetadata(videoId, "http://localhost:8080");

        assertFalse(result.isPresent());
    }
}
```

- [ ] **Step 4: Write `VideoStreamingServiceTest.java`**

```java
package com.videosharing.apiserver.service;

import com.videosharing.apiserver.entity.TranscodedFileEntity;
import com.videosharing.apiserver.entity.VideoEntity;
import com.videosharing.apiserver.repository.TranscodedFileRepository;
import com.videosharing.apiserver.repository.VideoRepository;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.StatObjectArgs;
import io.minio.StatObjectResponse;
import okhttp3.Headers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.io.ByteArrayInputStream;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class VideoStreamingServiceTest {

    @Mock private MinioClient minioClient;
    @Mock private VideoRepository videoRepository;
    @Mock private TranscodedFileRepository transcodedFileRepository;

    private VideoStreamingService streamingService;

    @BeforeEach
    void setUp() {
        streamingService = new VideoStreamingService(
                minioClient, videoRepository, transcodedFileRepository, "transcoded-storage");
    }

    @Test
    void streamVideo_returnsStream() throws Exception {
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, "test.mp4", "originals/" + videoId + "/test.mp4");
        TranscodedFileEntity file720 = new TranscodedFileEntity(
                UUID.randomUUID(), video, "720p", videoId + "/720p/test.mp4", 5000L);

        when(videoRepository.findByOriginalFilename("test.mp4")).thenReturn(Optional.of(video));
        when(transcodedFileRepository.findByVideoId(videoId)).thenReturn(List.of(file720));

        StatObjectResponse statResponse = mock(StatObjectResponse.class);
        when(statResponse.size()).thenReturn(5000L);
        when(minioClient.statObject(any(StatObjectArgs.class))).thenReturn(statResponse);

        GetObjectResponse getResponse = mock(GetObjectResponse.class);
        when(getResponse.read(any(byte[].class), anyInt(), anyInt())).thenReturn(-1);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(getResponse);

        Optional<VideoStreamingService.StreamResult> result = streamingService.streamVideo("test.mp4", "720p");

        assertTrue(result.isPresent());
        assertEquals(5000L, result.get().contentLength());
        assertEquals("video/mp4", result.get().contentType());
    }

    @Test
    void streamVideo_fileNotFound_returnsEmpty() {
        when(videoRepository.findByOriginalFilename("missing.mp4")).thenReturn(Optional.empty());

        Optional<VideoStreamingService.StreamResult> result = streamingService.streamVideo("missing.mp4", "720p");

        assertFalse(result.isPresent());
    }
}
```

- [ ] **Step 5: Write `VideoControllerTest.java`**

```java
package com.videosharing.apiserver.controller;

import com.videosharing.apiserver.service.VideoMetadataService;
import com.videosharing.apiserver.service.VideoStreamingService;
import com.videosharing.apiserver.service.VideoUploadService;
import com.videosharing.common.dto.VideoMetadata;
import com.videosharing.common.dto.VideoStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.bean.MockBean;
import org.springframework.core.io.InputStreamResource;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(VideoController.class)
class VideoControllerTest {

    @Autowired private MockMvc mockMvc;
    @MockBean private VideoUploadService uploadService;
    @MockBean private VideoMetadataService metadataService;
    @MockBean private VideoStreamingService streamingService;

    @Test
    void uploadVideo_returns202() throws Exception {
        UUID videoId = UUID.randomUUID();
        when(uploadService.uploadVideo(any())).thenReturn(videoId);

        MockMultipartFile file = new MockMultipartFile("file", "test.mp4", "video/mp4", new byte[100]);

        mockMvc.perform(multipart("/api/videos/upload").file(file))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.videoId").value(videoId.toString()))
                .andExpect(jsonPath("$.status").value("TRANSCODING"));
    }

    @Test
    void uploadVideo_emptyFile_returns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.mp4", "video/mp4", new byte[0]);

        mockMvc.perform(multipart("/api/videos/upload").file(file))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadVideo_nonMp4_returns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile("file", "test.avi", "video/avi", new byte[100]);

        mockMvc.perform(multipart("/api/videos/upload").file(file))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getVideoMetadata_returns200() throws Exception {
        UUID videoId = UUID.randomUUID();
        VideoMetadata metadata = new VideoMetadata(videoId, "test.mp4", VideoStatus.COMPLETED,
                List.of(new VideoMetadata.TranscodedFileReference("720p", "http://localhost/api/videos/stream?file=test.mp4&resolution=720p", 5000)),
                Instant.now());

        when(metadataService.getVideoMetadata(eq(videoId), anyString())).thenReturn(Optional.of(metadata));

        mockMvc.perform(get("/api/videos/" + videoId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.originalFilename").value("test.mp4"))
                .andExpect(jsonPath("$.transcodedFiles[0].resolution").value("720p"));
    }

    @Test
    void getVideoMetadata_notFound_returns404() throws Exception {
        UUID videoId = UUID.randomUUID();
        when(metadataService.getVideoMetadata(eq(videoId), anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/videos/" + videoId))
                .andExpect(status().isNotFound());
    }

    @Test
    void streamVideo_returns200() throws Exception {
        VideoStreamingService.StreamResult result = new VideoStreamingService.StreamResult(
                new InputStreamResource(new ByteArrayInputStream(new byte[100])), 100, "video/mp4");

        when(streamingService.streamVideo("test.mp4", "720p")).thenReturn(Optional.of(result));

        mockMvc.perform(get("/api/videos/stream").param("file", "test.mp4").param("resolution", "720p"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "video/mp4"));
    }

    @Test
    void listVideos_returns200() throws Exception {
        when(metadataService.listAllVideos(anyString())).thenReturn(List.of());

        mockMvc.perform(get("/api/videos"))
                .andExpect(status().isOk())
                .andExpect(content().json("[]"));
    }
}
```

- [ ] **Step 6: Run tests**

Run: `./gradlew :api-server:test`
Expected: All tests PASS

- [ ] **Step 7: Commit**

```bash
git add api-server/
git commit -m "feat: add api-server controller and unit tests"
```

---

### Task 5: Transcoding Worker

**Files:**
- Create: `transcoding-worker/build.gradle`
- Create: `transcoding-worker/src/main/java/com/videosharing/transcoding/TranscodingWorkerApplication.java`
- Create: `transcoding-worker/src/main/java/com/videosharing/transcoding/config/MinioClientConfig.java`
- Create: `transcoding-worker/src/main/java/com/videosharing/transcoding/config/RabbitMQConfig.java`
- Create: `transcoding-worker/src/main/java/com/videosharing/transcoding/service/FFmpegService.java`
- Create: `transcoding-worker/src/main/java/com/videosharing/transcoding/service/TranscodingService.java`
- Create: `transcoding-worker/src/main/java/com/videosharing/transcoding/listener/TranscodingTaskListener.java`
- Create: `transcoding-worker/src/main/resources/application.yml`
- Create: `transcoding-worker/src/test/java/com/videosharing/transcoding/service/FFmpegServiceTest.java`
- Create: `transcoding-worker/src/test/java/com/videosharing/transcoding/service/TranscodingServiceTest.java`

- [ ] **Step 1: Create `transcoding-worker/build.gradle`**

```groovy
plugins {
    id 'org.springframework.boot'
    id 'io.spring.dependency-management'
}

dependencies {
    implementation project(':common')

    implementation 'org.springframework.boot:spring-boot-starter-amqp'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'io.micrometer:micrometer-registry-prometheus'
    implementation 'io.minio:minio:8.5.12'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
}
```

- [ ] **Step 2: Create `TranscodingWorkerApplication.java`**

```java
package com.videosharing.transcoding;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class TranscodingWorkerApplication {
    public static void main(String[] args) {
        SpringApplication.run(TranscodingWorkerApplication.class, args);
    }
}
```

- [ ] **Step 3: Create `MinioClientConfig.java` (transcoding-worker copy)**

Same as api-server's `MinioClientConfig.java` but in `com.videosharing.transcoding.config` package.

```java
package com.videosharing.transcoding.config;

import io.minio.MinioClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class MinioClientConfig {

    @Value("${minio.endpoint}")
    private String endpoint;

    @Value("${minio.access-key}")
    private String accessKey;

    @Value("${minio.secret-key}")
    private String secretKey;

    @Bean
    public MinioClient minioClient() {
        return MinioClient.builder()
                .endpoint(endpoint)
                .credentials(accessKey, secretKey)
                .build();
    }
}
```

- [ ] **Step 4: Create `RabbitMQConfig.java` (transcoding-worker)**

```java
package com.videosharing.transcoding.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String TRANSCODING_TASKS_QUEUE = "transcoding-tasks";
    public static final String TRANSCODING_TASKS_DLQ = "transcoding-tasks-dlq";
    public static final String TRANSCODING_COMPLETIONS_QUEUE = "transcoding-completions";
    public static final String TRANSCODING_COMPLETIONS_DLQ = "transcoding-completions-dlq";
    public static final String DLX_EXCHANGE = "dlx-exchange";

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(DLX_EXCHANGE);
    }

    @Bean
    public Queue transcodingTasksQueue() {
        return QueueBuilder.durable(TRANSCODING_TASKS_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", TRANSCODING_TASKS_DLQ)
                .build();
    }

    @Bean
    public Queue transcodingTasksDlq() {
        return QueueBuilder.durable(TRANSCODING_TASKS_DLQ).build();
    }

    @Bean
    public Queue transcodingCompletionsQueue() {
        return QueueBuilder.durable(TRANSCODING_COMPLETIONS_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", TRANSCODING_COMPLETIONS_DLQ)
                .build();
    }

    @Bean
    public Queue transcodingCompletionsDlq() {
        return QueueBuilder.durable(TRANSCODING_COMPLETIONS_DLQ).build();
    }

    @Bean
    public Binding dlqTasksBinding() {
        return BindingBuilder.bind(transcodingTasksDlq()).to(dlxExchange()).with(TRANSCODING_TASKS_DLQ);
    }

    @Bean
    public Binding dlqCompletionsBinding() {
        return BindingBuilder.bind(transcodingCompletionsDlq()).to(dlxExchange()).with(TRANSCODING_COMPLETIONS_DLQ);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
```

- [ ] **Step 5: Create `FFmpegService.java`**

```java
package com.videosharing.transcoding.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.Map;

@Service
public class FFmpegService {

    private static final Logger log = LoggerFactory.getLogger(FFmpegService.class);

    private static final Map<String, String> RESOLUTION_MAP = Map.of(
            "144p", "256:144",
            "360p", "640:360",
            "720p", "1280:720"
    );

    public void transcode(Path inputFile, Path outputFile, String resolution) throws Exception {
        String scale = RESOLUTION_MAP.get(resolution);
        if (scale == null) {
            throw new IllegalArgumentException("Unsupported resolution: " + resolution);
        }

        ProcessBuilder pb = new ProcessBuilder(
                "ffmpeg", "-i", inputFile.toString(),
                "-vf", "scale=" + scale,
                "-c:v", "libx264", "-preset", "fast", "-crf", "28",
                "-c:a", "aac", "-b:a", "64k",
                "-y", outputFile.toString()
        );
        pb.redirectErrorStream(true);

        log.info("Starting FFmpeg transcode: {} -> {} ({})", inputFile.getFileName(), outputFile.getFileName(), resolution);

        Process process = pb.start();
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line;
            while ((line = reader.readLine()) != null) {
                log.debug("FFmpeg: {}", line);
            }
        }

        int exitCode = process.waitFor();
        if (exitCode != 0) {
            throw new RuntimeException("FFmpeg exited with code " + exitCode + " for resolution " + resolution);
        }

        log.info("FFmpeg transcode complete: {} ({})", outputFile.getFileName(), resolution);
    }
}
```

- [ ] **Step 6: Create `TranscodingService.java`**

```java
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
```

- [ ] **Step 7: Create `TranscodingTaskListener.java`**

```java
package com.videosharing.transcoding.listener;

import com.videosharing.common.dto.TranscodingTask;
import com.videosharing.transcoding.config.RabbitMQConfig;
import com.videosharing.transcoding.service.TranscodingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class TranscodingTaskListener {

    private static final Logger log = LoggerFactory.getLogger(TranscodingTaskListener.class);

    private final TranscodingService transcodingService;

    public TranscodingTaskListener(TranscodingService transcodingService) {
        this.transcodingService = transcodingService;
    }

    @RabbitListener(queues = RabbitMQConfig.TRANSCODING_TASKS_QUEUE)
    public void onTranscodingTask(TranscodingTask task) {
        log.info("Received transcoding task: videoId={}, filename={}", task.videoId(), task.originalFilename());
        try {
            transcodingService.processTranscodingTask(task);
        } catch (Exception e) {
            log.error("Transcoding failed: videoId={}", task.videoId(), e);
            throw new RuntimeException("Transcoding failed for " + task.videoId(), e);
        }
    }
}
```

- [ ] **Step 8: Create `application.yml` (transcoding-worker)**

```yaml
server:
  port: 8082

spring:
  application:
    name: transcoding-worker
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: 5672
    username: ${RABBITMQ_USER:guest}
    password: ${RABBITMQ_PASSWORD:guest}
    listener:
      simple:
        retry:
          enabled: true
          initial-interval: 1000
          multiplier: 2
          max-attempts: 3
        default-requeue-rejected: false
  autoconfigure:
    exclude:
      - org.springframework.boot.autoconfigure.jdbc.DataSourceAutoConfiguration
      - org.springframework.boot.autoconfigure.orm.jpa.HibernateJpaAutoConfiguration

minio:
  endpoint: http://${MINIO_HOST:localhost}:9000
  access-key: ${MINIO_ACCESS_KEY:minioadmin}
  secret-key: ${MINIO_SECRET_KEY:minioadmin}
  original-bucket: original-storage
  transcoded-bucket: transcoded-storage

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info
  metrics:
    tags:
      application: transcoding-worker
```

- [ ] **Step 9: Write `FFmpegServiceTest.java`**

```java
package com.videosharing.transcoding.service;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.*;

class FFmpegServiceTest {

    private final FFmpegService ffmpegService = new FFmpegService();

    @Test
    void transcode_invalidResolution_throwsException() {
        Path input = Path.of("/tmp/nonexistent.mp4");
        Path output = Path.of("/tmp/output.mp4");

        assertThrows(IllegalArgumentException.class,
                () -> ffmpegService.transcode(input, output, "999p"));
    }

    @Test
    void transcode_validResolutions_accepted() {
        // Verify all supported resolutions are accepted (no exception on resolution validation)
        // Actual FFmpeg execution is tested in integration tests
        for (String res : java.util.List.of("144p", "360p", "720p")) {
            assertDoesNotThrow(() -> {
                try {
                    ffmpegService.transcode(Path.of("/nonexistent"), Path.of("/tmp/out.mp4"), res);
                } catch (Exception e) {
                    if (e instanceof IllegalArgumentException) throw e;
                    // Expected: FFmpeg process fails on nonexistent file, but resolution was accepted
                }
            });
        }
    }
}
```

- [ ] **Step 10: Write `TranscodingServiceTest.java`**

```java
package com.videosharing.transcoding.service;

import com.videosharing.common.dto.TranscodedFileInfo;
import com.videosharing.common.dto.TranscodingCompletion;
import com.videosharing.common.dto.TranscodingTask;
import com.videosharing.transcoding.config.RabbitMQConfig;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.ObjectWriteResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.io.ByteArrayInputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TranscodingServiceTest {

    @Mock private MinioClient minioClient;
    @Mock private FFmpegService ffmpegService;
    @Mock private RabbitTemplate rabbitTemplate;

    private TranscodingService transcodingService;

    @BeforeEach
    void setUp() {
        transcodingService = new TranscodingService(
                minioClient, ffmpegService, rabbitTemplate,
                "original-storage", "transcoded-storage",
                new SimpleMeterRegistry());
    }

    @Test
    void processTranscodingTask_callsFFmpegForEachResolution(@TempDir Path tempDir) throws Exception {
        UUID videoId = UUID.randomUUID();
        TranscodingTask task = new TranscodingTask(videoId, "test.mp4", "originals/" + videoId + "/test.mp4");

        GetObjectResponse getResponse = mock(GetObjectResponse.class);
        when(getResponse.readAllBytes()).thenReturn(new byte[100]);
        when(minioClient.getObject(any(GetObjectArgs.class))).thenReturn(getResponse);
        when(minioClient.putObject(any(PutObjectArgs.class))).thenReturn(mock(ObjectWriteResponse.class));

        // Mock FFmpeg to create dummy output files
        doAnswer(invocation -> {
            Path outputFile = invocation.getArgument(1);
            Files.write(outputFile, new byte[50]);
            return null;
        }).when(ffmpegService).transcode(any(), any(), any());

        transcodingService.processTranscodingTask(task);

        // Verify FFmpeg called for each resolution
        verify(ffmpegService).transcode(any(), any(), eq("144p"));
        verify(ffmpegService).transcode(any(), any(), eq("360p"));
        verify(ffmpegService).transcode(any(), any(), eq("720p"));

        // Verify completion published
        verify(rabbitTemplate).convertAndSend(eq(RabbitMQConfig.TRANSCODING_COMPLETIONS_QUEUE), any(TranscodingCompletion.class));

        // Verify 3 uploads to MinIO (one per resolution)
        verify(minioClient, times(3)).putObject(any(PutObjectArgs.class));
    }
}
```

- [ ] **Step 11: Write `TranscodingTaskListenerTest.java`**

```java
package com.videosharing.transcoding.listener;

import com.videosharing.common.dto.TranscodingTask;
import com.videosharing.transcoding.service.TranscodingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TranscodingTaskListenerTest {

    @Mock private TranscodingService transcodingService;
    @InjectMocks private TranscodingTaskListener listener;

    @Test
    void onTranscodingTask_delegatesToService() throws Exception {
        UUID videoId = UUID.randomUUID();
        TranscodingTask task = new TranscodingTask(videoId, "test.mp4", "originals/" + videoId + "/test.mp4");

        listener.onTranscodingTask(task);

        verify(transcodingService).processTranscodingTask(task);
    }

    @Test
    void onTranscodingTask_serviceThrows_propagates() throws Exception {
        UUID videoId = UUID.randomUUID();
        TranscodingTask task = new TranscodingTask(videoId, "test.mp4", "originals/" + videoId + "/test.mp4");

        doThrow(new RuntimeException("FFmpeg failed")).when(transcodingService).processTranscodingTask(task);

        assertThrows(RuntimeException.class, () -> listener.onTranscodingTask(task));
    }
}
```

- [ ] **Step 12: Run tests**

Run: `./gradlew :transcoding-worker:test`
Expected: All tests PASS

- [ ] **Step 13: Commit**

```bash
git add transcoding-worker/
git commit -m "feat: add transcoding-worker with FFmpeg service and RabbitMQ listener"
```

---

### Task 6: Completion Handler

**Files:**
- Create: `completion-handler/build.gradle`
- Create: `completion-handler/src/main/java/com/videosharing/completion/CompletionHandlerApplication.java`
- Create: `completion-handler/src/main/java/com/videosharing/completion/config/RabbitMQConfig.java`
- Create: `completion-handler/src/main/java/com/videosharing/completion/entity/VideoEntity.java`
- Create: `completion-handler/src/main/java/com/videosharing/completion/entity/TranscodedFileEntity.java`
- Create: `completion-handler/src/main/java/com/videosharing/completion/repository/VideoRepository.java`
- Create: `completion-handler/src/main/java/com/videosharing/completion/repository/TranscodedFileRepository.java`
- Create: `completion-handler/src/main/java/com/videosharing/completion/service/CompletionService.java`
- Create: `completion-handler/src/main/java/com/videosharing/completion/listener/CompletionListener.java`
- Create: `completion-handler/src/main/resources/application.yml`
- Create: `completion-handler/src/test/java/com/videosharing/completion/service/CompletionServiceTest.java`

- [ ] **Step 1: Create `completion-handler/build.gradle`**

```groovy
plugins {
    id 'org.springframework.boot'
    id 'io.spring.dependency-management'
}

dependencies {
    implementation project(':common')

    implementation 'org.springframework.boot:spring-boot-starter-amqp'
    implementation 'org.springframework.boot:spring-boot-starter-data-jpa'
    implementation 'org.springframework.boot:spring-boot-starter-data-redis'
    implementation 'org.springframework.boot:spring-boot-starter-actuator'
    implementation 'io.micrometer:micrometer-registry-prometheus'

    runtimeOnly 'org.postgresql:postgresql'

    testImplementation 'org.springframework.boot:spring-boot-starter-test'
}
```

- [ ] **Step 2: Create `CompletionHandlerApplication.java`**

```java
package com.videosharing.completion;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class CompletionHandlerApplication {
    public static void main(String[] args) {
        SpringApplication.run(CompletionHandlerApplication.class, args);
    }
}
```

- [ ] **Step 3: Create `RabbitMQConfig.java` (completion-handler)**

```java
package com.videosharing.completion.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String TRANSCODING_COMPLETIONS_QUEUE = "transcoding-completions";
    public static final String TRANSCODING_COMPLETIONS_DLQ = "transcoding-completions-dlq";
    public static final String DLX_EXCHANGE = "dlx-exchange";

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(DLX_EXCHANGE);
    }

    @Bean
    public Queue transcodingCompletionsQueue() {
        return QueueBuilder.durable(TRANSCODING_COMPLETIONS_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", TRANSCODING_COMPLETIONS_DLQ)
                .build();
    }

    @Bean
    public Queue transcodingCompletionsDlq() {
        return QueueBuilder.durable(TRANSCODING_COMPLETIONS_DLQ).build();
    }

    @Bean
    public Binding dlqCompletionsBinding() {
        return BindingBuilder.bind(transcodingCompletionsDlq()).to(dlxExchange()).with(TRANSCODING_COMPLETIONS_DLQ);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
```

- [ ] **Step 3b: Create `VideoEntity.java` (completion-handler)**

```java
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

    public UUID getId() { return id; }
    public VideoStatus getStatus() { return status; }
    public void setStatus(VideoStatus status) { this.status = status; this.updatedAt = Instant.now(); }
    public List<TranscodedFileEntity> getTranscodedFiles() { return transcodedFiles; }

    @PreUpdate
    protected void onUpdate() { this.updatedAt = Instant.now(); }
}
```

- [ ] **Step 3c: Create `TranscodedFileEntity.java` (completion-handler)**

```java
package com.videosharing.completion.entity;

import jakarta.persistence.*;
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
```

- [ ] **Step 3d: Create repositories (completion-handler)**

`VideoRepository.java`:
```java
package com.videosharing.completion.repository;

import com.videosharing.completion.entity.VideoEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface VideoRepository extends JpaRepository<VideoEntity, UUID> {
}
```

`TranscodedFileRepository.java`:
```java
package com.videosharing.completion.repository;

import com.videosharing.completion.entity.TranscodedFileEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;

public interface TranscodedFileRepository extends JpaRepository<TranscodedFileEntity, UUID> {
}
```

- [ ] **Step 4: Create `CompletionService.java`**

```java
package com.videosharing.completion.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.videosharing.common.dto.TranscodedFileInfo;
import com.videosharing.common.dto.TranscodingCompletion;
import com.videosharing.common.dto.VideoStatus;
import com.videosharing.completion.entity.TranscodedFileEntity;
import com.videosharing.completion.entity.VideoEntity;
import com.videosharing.completion.repository.TranscodedFileRepository;
import com.videosharing.completion.repository.VideoRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.util.UUID;

@Service
public class CompletionService {

    private static final Logger log = LoggerFactory.getLogger(CompletionService.class);
    private static final String CACHE_PREFIX = "video:metadata:";

    private final VideoRepository videoRepository;
    private final TranscodedFileRepository transcodedFileRepository;
    private final StringRedisTemplate redisTemplate;

    public CompletionService(VideoRepository videoRepository,
                             TranscodedFileRepository transcodedFileRepository,
                             StringRedisTemplate redisTemplate) {
        this.videoRepository = videoRepository;
        this.transcodedFileRepository = transcodedFileRepository;
        this.redisTemplate = redisTemplate;
    }

    @Transactional
    public void handleCompletion(TranscodingCompletion completion) {
        VideoEntity video = videoRepository.findById(completion.videoId())
                .orElseThrow(() -> new IllegalStateException("Video not found: " + completion.videoId()));

        // Insert transcoded file records
        for (TranscodedFileInfo fileInfo : completion.transcodedFiles()) {
            TranscodedFileEntity entity = new TranscodedFileEntity(
                    UUID.randomUUID(),
                    video,
                    fileInfo.resolution(),
                    fileInfo.minioKey(),
                    fileInfo.fileSize()
            );
            transcodedFileRepository.save(entity);
        }

        // Update video status
        video.setStatus(VideoStatus.COMPLETED);
        videoRepository.save(video);

        // Invalidate cache so next read gets fresh data
        try {
            redisTemplate.delete(CACHE_PREFIX + completion.videoId());
        } catch (Exception e) {
            log.warn("Failed to invalidate cache for video {}: {}", completion.videoId(), e.getMessage());
        }

        log.info("Completion handled: videoId={}, transcodedFiles={}", completion.videoId(), completion.transcodedFiles().size());
    }
}
```

- [ ] **Step 5: Create `CompletionListener.java`**

```java
package com.videosharing.completion.listener;

import com.videosharing.common.dto.TranscodingCompletion;
import com.videosharing.completion.config.RabbitMQConfig;
import com.videosharing.completion.service.CompletionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class CompletionListener {

    private static final Logger log = LoggerFactory.getLogger(CompletionListener.class);

    private final CompletionService completionService;

    public CompletionListener(CompletionService completionService) {
        this.completionService = completionService;
    }

    @RabbitListener(queues = RabbitMQConfig.TRANSCODING_COMPLETIONS_QUEUE)
    public void onCompletion(TranscodingCompletion completion) {
        log.info("Received completion: videoId={}", completion.videoId());
        try {
            completionService.handleCompletion(completion);
        } catch (Exception e) {
            log.error("Completion handling failed: videoId={}", completion.videoId(), e);
            throw new RuntimeException("Completion handling failed for " + completion.videoId(), e);
        }
    }
}
```

- [ ] **Step 6: Create `application.yml` (completion-handler)**

```yaml
server:
  port: 8083

spring:
  application:
    name: completion-handler
  datasource:
    url: jdbc:postgresql://${POSTGRES_HOST:localhost}:5432/${POSTGRES_DB:videodb}
    username: ${POSTGRES_USER:videouser}
    password: ${POSTGRES_PASSWORD:videopass}
  jpa:
    hibernate:
      ddl-auto: none
    open-in-view: false
  rabbitmq:
    host: ${RABBITMQ_HOST:localhost}
    port: 5672
    username: ${RABBITMQ_USER:guest}
    password: ${RABBITMQ_PASSWORD:guest}
    listener:
      simple:
        retry:
          enabled: true
          initial-interval: 1000
          multiplier: 2
          max-attempts: 3
        default-requeue-rejected: false
  data:
    redis:
      host: ${REDIS_HOST:localhost}
      port: 6379

management:
  endpoints:
    web:
      exposure:
        include: health,prometheus,info
  metrics:
    tags:
      application: completion-handler
```

- [ ] **Step 7: Write `CompletionServiceTest.java`**

```java
package com.videosharing.completion.service;

import com.videosharing.common.dto.TranscodedFileInfo;
import com.videosharing.common.dto.TranscodingCompletion;
import com.videosharing.common.dto.VideoStatus;
import com.videosharing.completion.entity.VideoEntity;
import com.videosharing.completion.entity.TranscodedFileEntity;
import com.videosharing.completion.repository.TranscodedFileRepository;
import com.videosharing.completion.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompletionServiceTest {

    @Mock private VideoRepository videoRepository;
    @Mock private TranscodedFileRepository transcodedFileRepository;
    @Mock private StringRedisTemplate redisTemplate;

    private CompletionService completionService;

    @BeforeEach
    void setUp() {
        completionService = new CompletionService(videoRepository, transcodedFileRepository, redisTemplate);
    }

    @Test
    void handleCompletion_savesTranscodedFilesAndUpdatesStatus() {
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, "test.mp4", "originals/" + videoId + "/test.mp4");
        video.setStatus(VideoStatus.TRANSCODING);

        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));
        when(transcodedFileRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(videoRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        TranscodingCompletion completion = new TranscodingCompletion(videoId, "test.mp4", List.of(
                new TranscodedFileInfo("144p", videoId + "/144p/test.mp4", 1000),
                new TranscodedFileInfo("360p", videoId + "/360p/test.mp4", 2000),
                new TranscodedFileInfo("720p", videoId + "/720p/test.mp4", 3000)
        ));

        completionService.handleCompletion(completion);

        // Verify 3 transcoded files saved
        verify(transcodedFileRepository, times(3)).save(any(TranscodedFileEntity.class));

        // Verify video status updated to COMPLETED
        ArgumentCaptor<VideoEntity> captor = ArgumentCaptor.forClass(VideoEntity.class);
        verify(videoRepository).save(captor.capture());
        assertEquals(VideoStatus.COMPLETED, captor.getValue().getStatus());

        // Verify cache invalidated
        verify(redisTemplate).delete("video:metadata:" + videoId);
    }

    @Test
    void handleCompletion_videoNotFound_throws() {
        UUID videoId = UUID.randomUUID();
        when(videoRepository.findById(videoId)).thenReturn(Optional.empty());

        TranscodingCompletion completion = new TranscodingCompletion(videoId, "test.mp4", List.of());

        assertThrows(IllegalStateException.class, () -> completionService.handleCompletion(completion));
    }
}
```

- [ ] **Step 8: Write `CompletionListenerTest.java`**

```java
package com.videosharing.completion.listener;

import com.videosharing.common.dto.TranscodedFileInfo;
import com.videosharing.common.dto.TranscodingCompletion;
import com.videosharing.completion.service.CompletionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompletionListenerTest {

    @Mock private CompletionService completionService;
    @InjectMocks private CompletionListener completionListener;

    @Test
    void onCompletion_delegatesToService() {
        UUID videoId = UUID.randomUUID();
        TranscodingCompletion completion = new TranscodingCompletion(videoId, "test.mp4", List.of(
                new TranscodedFileInfo("720p", videoId + "/720p/test.mp4", 5000)
        ));

        completionListener.onCompletion(completion);

        verify(completionService).handleCompletion(completion);
    }

    @Test
    void onCompletion_serviceThrows_propagates() {
        UUID videoId = UUID.randomUUID();
        TranscodingCompletion completion = new TranscodingCompletion(videoId, "test.mp4", List.of());

        doThrow(new IllegalStateException("Video not found")).when(completionService).handleCompletion(completion);

        assertThrows(RuntimeException.class, () -> completionListener.onCompletion(completion));
    }
}
```

- [ ] **Step 9: Run tests**

Run: `./gradlew :completion-handler:test`
Expected: All tests PASS

- [ ] **Step 10: Commit**

```bash
git add completion-handler/
git commit -m "feat: add completion-handler with service, listener, and unit tests"
```

---

### Task 7: Infrastructure Configs (Nginx, Prometheus, Grafana)

**Files:**
- Create: `nginx/nginx.conf`
- Create: `prometheus/prometheus.yml`
- Create: `grafana/provisioning/datasources/prometheus.yml`
- Create: `grafana/provisioning/dashboards/dashboard.yml`
- Create: `grafana/dashboards/video-platform.json`

- [ ] **Step 1: Create `nginx/nginx.conf`**

```nginx
upstream api_servers {
    server api-server-1:8080 max_fails=3 fail_timeout=30s;
    server api-server-2:8080 max_fails=3 fail_timeout=30s;
}

server {
    listen 80;
    client_max_body_size 500M;

    proxy_connect_timeout 5s;
    proxy_read_timeout 300s;
    proxy_send_timeout 300s;

    location /api/ {
        proxy_pass http://api_servers;
        proxy_set_header Host $host;
        proxy_set_header X-Real-IP $remote_addr;
        proxy_set_header X-Forwarded-For $proxy_add_x_forwarded_for;
        proxy_set_header X-Forwarded-Proto $scheme;
        proxy_buffering off;
    }

    location /actuator/ {
        proxy_pass http://api_servers;
    }

    location /health {
        access_log off;
        return 200 'OK';
        add_header Content-Type text/plain;
    }
}
```

- [ ] **Step 2: Create `prometheus/prometheus.yml`**

```yaml
global:
  scrape_interval: 15s
  evaluation_interval: 15s

scrape_configs:
  - job_name: 'api-server'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets:
          - 'api-server-1:8080'
          - 'api-server-2:8080'

  - job_name: 'transcoding-worker'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets:
          - 'transcoding-worker-1:8082'
          - 'transcoding-worker-2:8082'

  - job_name: 'completion-handler'
    metrics_path: '/actuator/prometheus'
    static_configs:
      - targets:
          - 'completion-handler-1:8083'
          - 'completion-handler-2:8083'
```

- [ ] **Step 3: Create Grafana provisioning files**

`grafana/provisioning/datasources/prometheus.yml`:
```yaml
apiVersion: 1

datasources:
  - name: Prometheus
    type: prometheus
    access: proxy
    url: http://prometheus:9090
    isDefault: true
    editable: false
```

`grafana/provisioning/dashboards/dashboard.yml`:
```yaml
apiVersion: 1

providers:
  - name: 'default'
    orgId: 1
    folder: ''
    type: file
    disableDeletion: false
    updateIntervalSeconds: 10
    options:
      path: /var/lib/grafana/dashboards
      foldersFromFilesStructure: false
```

- [ ] **Step 4: Create `grafana/dashboards/video-platform.json`**

```json
{
  "annotations": { "list": [] },
  "editable": true,
  "fiscalYearStartMonth": 0,
  "graphTooltip": 1,
  "id": null,
  "links": [],
  "panels": [
    {
      "collapsed": false,
      "gridPos": { "h": 1, "w": 24, "x": 0, "y": 0 },
      "id": 100,
      "title": "API Overview",
      "type": "row"
    },
    {
      "datasource": { "type": "prometheus", "uid": "PBFA97CFB590B2093" },
      "fieldConfig": { "defaults": { "color": { "mode": "palette-classic" }, "custom": { "axisBorderShow": false, "drawStyle": "line", "fillOpacity": 10, "lineWidth": 2 }, "unit": "reqps" } },
      "gridPos": { "h": 8, "w": 8, "x": 0, "y": 1 },
      "id": 1,
      "title": "Request Rate",
      "type": "timeseries",
      "targets": [{ "expr": "sum(rate(http_server_requests_seconds_count{application=\"api-server\"}[1m]))", "legendFormat": "Total req/s" }]
    },
    {
      "datasource": { "type": "prometheus", "uid": "PBFA97CFB590B2093" },
      "fieldConfig": { "defaults": { "color": { "mode": "palette-classic" }, "custom": { "drawStyle": "line", "fillOpacity": 10, "lineWidth": 2 }, "unit": "s" } },
      "gridPos": { "h": 8, "w": 8, "x": 8, "y": 1 },
      "id": 2,
      "title": "Latency (p50 / p95 / p99)",
      "type": "timeseries",
      "targets": [
        { "expr": "histogram_quantile(0.50, sum(rate(http_server_requests_seconds_bucket{application=\"api-server\"}[5m])) by (le))", "legendFormat": "p50" },
        { "expr": "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{application=\"api-server\"}[5m])) by (le))", "legendFormat": "p95" },
        { "expr": "histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket{application=\"api-server\"}[5m])) by (le))", "legendFormat": "p99" }
      ]
    },
    {
      "datasource": { "type": "prometheus", "uid": "PBFA97CFB590B2093" },
      "fieldConfig": { "defaults": { "color": { "mode": "palette-classic" }, "custom": { "drawStyle": "line", "fillOpacity": 10, "lineWidth": 2 }, "unit": "reqps" } },
      "gridPos": { "h": 8, "w": 8, "x": 16, "y": 1 },
      "id": 3,
      "title": "Error Rate (5xx)",
      "type": "timeseries",
      "targets": [{ "expr": "sum(rate(http_server_requests_seconds_count{application=\"api-server\",status=~\"5..\"}[1m]))", "legendFormat": "5xx/s" }]
    },
    {
      "collapsed": false,
      "gridPos": { "h": 1, "w": 24, "x": 0, "y": 9 },
      "id": 101,
      "title": "Upload Metrics",
      "type": "row"
    },
    {
      "datasource": { "type": "prometheus", "uid": "PBFA97CFB590B2093" },
      "fieldConfig": { "defaults": { "color": { "mode": "palette-classic" }, "custom": { "drawStyle": "bars", "fillOpacity": 50, "lineWidth": 1 }, "unit": "short" } },
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 10 },
      "id": 4,
      "title": "Upload Count",
      "type": "timeseries",
      "targets": [{ "expr": "increase(video_upload_total[5m])", "legendFormat": "Uploads / 5m" }]
    },
    {
      "datasource": { "type": "prometheus", "uid": "PBFA97CFB590B2093" },
      "fieldConfig": { "defaults": { "color": { "mode": "palette-classic" }, "custom": { "drawStyle": "line", "fillOpacity": 10, "lineWidth": 2 }, "unit": "bytes" } },
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 10 },
      "id": 5,
      "title": "Upload Size",
      "type": "timeseries",
      "targets": [{ "expr": "video_upload_size_bytes_sum / video_upload_size_bytes_count", "legendFormat": "Avg upload size" }]
    },
    {
      "collapsed": false,
      "gridPos": { "h": 1, "w": 24, "x": 0, "y": 18 },
      "id": 102,
      "title": "Transcoding",
      "type": "row"
    },
    {
      "datasource": { "type": "prometheus", "uid": "PBFA97CFB590B2093" },
      "fieldConfig": { "defaults": { "color": { "mode": "palette-classic" }, "thresholds": { "steps": [{ "color": "green", "value": null }, { "color": "red", "value": 5 }] } }, "overrides": [] },
      "gridPos": { "h": 8, "w": 8, "x": 0, "y": 19 },
      "id": 6,
      "title": "Active Transcoding Jobs",
      "type": "gauge",
      "targets": [{ "expr": "sum(transcoding_jobs_active)", "legendFormat": "Active" }]
    },
    {
      "datasource": { "type": "prometheus", "uid": "PBFA97CFB590B2093" },
      "fieldConfig": { "defaults": { "color": { "mode": "palette-classic" }, "custom": { "drawStyle": "line", "fillOpacity": 10, "lineWidth": 2 }, "unit": "s" } },
      "gridPos": { "h": 8, "w": 8, "x": 8, "y": 19 },
      "id": 7,
      "title": "Transcoding Duration (p95) by Resolution",
      "type": "timeseries",
      "targets": [
        { "expr": "histogram_quantile(0.95, sum(rate(transcoding_duration_seconds_bucket{resolution=\"144p\"}[5m])) by (le))", "legendFormat": "144p p95" },
        { "expr": "histogram_quantile(0.95, sum(rate(transcoding_duration_seconds_bucket{resolution=\"360p\"}[5m])) by (le))", "legendFormat": "360p p95" },
        { "expr": "histogram_quantile(0.95, sum(rate(transcoding_duration_seconds_bucket{resolution=\"720p\"}[5m])) by (le))", "legendFormat": "720p p95" }
      ]
    },
    {
      "datasource": { "type": "prometheus", "uid": "PBFA97CFB590B2093" },
      "fieldConfig": { "defaults": { "color": { "mode": "palette-classic" }, "custom": { "drawStyle": "line", "fillOpacity": 10, "lineWidth": 2 }, "unit": "short" } },
      "gridPos": { "h": 8, "w": 8, "x": 16, "y": 19 },
      "id": 8,
      "title": "Transcoding Success / Failure Rate",
      "type": "timeseries",
      "targets": [
        { "expr": "sum(rate(http_server_requests_seconds_count{application=\"transcoding-worker\",status=~\"2..\"}[1m]))", "legendFormat": "Success" },
        { "expr": "sum(rate(http_server_requests_seconds_count{application=\"transcoding-worker\",status=~\"5..\"}[1m]))", "legendFormat": "Failure" }
      ]
    },
    {
      "collapsed": false,
      "gridPos": { "h": 1, "w": 24, "x": 0, "y": 27 },
      "id": 103,
      "title": "Streaming",
      "type": "row"
    },
    {
      "datasource": { "type": "prometheus", "uid": "PBFA97CFB590B2093" },
      "fieldConfig": { "defaults": { "color": { "mode": "palette-classic" }, "custom": { "drawStyle": "line", "fillOpacity": 10, "lineWidth": 2 }, "unit": "reqps" } },
      "gridPos": { "h": 8, "w": 12, "x": 0, "y": 28 },
      "id": 9,
      "title": "Stream Request Rate",
      "type": "timeseries",
      "targets": [{ "expr": "sum(rate(http_server_requests_seconds_count{application=\"api-server\",uri=\"/api/videos/stream\"}[1m]))", "legendFormat": "Stream req/s" }]
    },
    {
      "datasource": { "type": "prometheus", "uid": "PBFA97CFB590B2093" },
      "fieldConfig": { "defaults": { "color": { "mode": "palette-classic" }, "custom": { "drawStyle": "line", "fillOpacity": 10, "lineWidth": 2 }, "unit": "s" } },
      "gridPos": { "h": 8, "w": 12, "x": 12, "y": 28 },
      "id": 10,
      "title": "Stream Latency (p95)",
      "type": "timeseries",
      "targets": [{ "expr": "histogram_quantile(0.95, sum(rate(http_server_requests_seconds_bucket{application=\"api-server\",uri=\"/api/videos/stream\"}[5m])) by (le))", "legendFormat": "p95" }]
    },
    {
      "collapsed": false,
      "gridPos": { "h": 1, "w": 24, "x": 0, "y": 36 },
      "id": 104,
      "title": "JVM Metrics",
      "type": "row"
    },
    {
      "datasource": { "type": "prometheus", "uid": "PBFA97CFB590B2093" },
      "fieldConfig": { "defaults": { "color": { "mode": "palette-classic" }, "custom": { "drawStyle": "line", "fillOpacity": 10, "lineWidth": 2 }, "unit": "bytes" } },
      "gridPos": { "h": 8, "w": 8, "x": 0, "y": 37 },
      "id": 11,
      "title": "JVM Heap Usage",
      "type": "timeseries",
      "targets": [{ "expr": "jvm_memory_used_bytes{area=\"heap\"}", "legendFormat": "{{instance}} {{application}}" }]
    },
    {
      "datasource": { "type": "prometheus", "uid": "PBFA97CFB590B2093" },
      "fieldConfig": { "defaults": { "color": { "mode": "palette-classic" }, "custom": { "drawStyle": "line", "fillOpacity": 10, "lineWidth": 2 }, "unit": "s" } },
      "gridPos": { "h": 8, "w": 8, "x": 8, "y": 37 },
      "id": 12,
      "title": "GC Pause Time",
      "type": "timeseries",
      "targets": [{ "expr": "rate(jvm_gc_pause_seconds_sum[1m])", "legendFormat": "{{instance}} {{application}}" }]
    },
    {
      "datasource": { "type": "prometheus", "uid": "PBFA97CFB590B2093" },
      "fieldConfig": { "defaults": { "color": { "mode": "palette-classic" }, "custom": { "drawStyle": "line", "fillOpacity": 10, "lineWidth": 2 }, "unit": "short" } },
      "gridPos": { "h": 8, "w": 8, "x": 16, "y": 37 },
      "id": 13,
      "title": "Thread Count",
      "type": "timeseries",
      "targets": [{ "expr": "jvm_threads_live_threads", "legendFormat": "{{instance}} {{application}}" }]
    }
  ],
  "schemaVersion": 39,
  "tags": ["video-platform"],
  "templating": { "list": [] },
  "time": { "from": "now-30m", "to": "now" },
  "timepicker": {},
  "timezone": "browser",
  "title": "Video Sharing Platform",
  "uid": "video-platform",
  "version": 1
}
```

- [ ] **Step 5: Commit**

```bash
git add nginx/ prometheus/ grafana/
git commit -m "feat: add nginx, prometheus, and grafana infrastructure configs"
```

---

### Task 8: Dockerfiles & docker-compose.yml

**Files:**
- Create: `api-server/Dockerfile`
- Create: `transcoding-worker/Dockerfile`
- Create: `completion-handler/Dockerfile`
- Create: `load-client/Dockerfile`
- Create: `docker-compose.yml`

- [ ] **Step 1: Create `api-server/Dockerfile`**

```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY build/libs/*.jar app.jar
EXPOSE 8080
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 2: Create `transcoding-worker/Dockerfile`**

```dockerfile
FROM eclipse-temurin:21-jre-alpine
RUN apk add --no-cache ffmpeg
WORKDIR /app
COPY build/libs/*.jar app.jar
EXPOSE 8082
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 3: Create `completion-handler/Dockerfile`**

```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY build/libs/*.jar app.jar
EXPOSE 8083
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 4: Create `load-client/Dockerfile`**

```dockerfile
FROM eclipse-temurin:21-jre-alpine
WORKDIR /app
COPY build/libs/*.jar app.jar
ENTRYPOINT ["java", "-jar", "app.jar"]
```

- [ ] **Step 5: Create `docker-compose.yml`**

```yaml
services:
  # --- Infrastructure ---
  postgresql:
    image: postgres:16-alpine
    environment:
      POSTGRES_DB: videodb
      POSTGRES_USER: videouser
      POSTGRES_PASSWORD: videopass
    ports:
      - "5432:5432"
    volumes:
      - postgres-data:/var/lib/postgresql/data
      - ./api-server/src/main/resources/schema.sql:/docker-entrypoint-initdb.d/schema.sql
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U videouser -d videodb"]
      interval: 5s
      timeout: 5s
      retries: 5

  redis:
    image: redis:7-alpine
    ports:
      - "6379:6379"
    healthcheck:
      test: ["CMD", "redis-cli", "ping"]
      interval: 5s
      timeout: 5s
      retries: 5

  rabbitmq:
    image: rabbitmq:3-management
    ports:
      - "5672:5672"
      - "15672:15672"
    environment:
      RABBITMQ_DEFAULT_USER: guest
      RABBITMQ_DEFAULT_PASS: guest
    healthcheck:
      test: ["CMD", "rabbitmq-diagnostics", "-q", "ping"]
      interval: 10s
      timeout: 10s
      retries: 5

  minio:
    image: minio/minio
    command: server /data --console-address ":9001"
    ports:
      - "9000:9000"
      - "9001:9001"
    environment:
      MINIO_ROOT_USER: minioadmin
      MINIO_ROOT_PASSWORD: minioadmin
    volumes:
      - minio-data:/data
    healthcheck:
      test: ["CMD-SHELL", "curl -f http://localhost:9000/minio/health/live || exit 1"]
      interval: 5s
      timeout: 5s
      retries: 5

  minio-init:
    image: minio/mc
    depends_on:
      minio:
        condition: service_healthy
    entrypoint: >
      /bin/sh -c "
      mc alias set local http://minio:9000 minioadmin minioadmin;
      mc mb local/original-storage --ignore-existing;
      mc mb local/transcoded-storage --ignore-existing;
      exit 0;
      "

  # --- Application Services ---
  api-server-1:
    build: ./api-server
    environment:
      POSTGRES_HOST: postgresql
      POSTGRES_DB: videodb
      POSTGRES_USER: videouser
      POSTGRES_PASSWORD: videopass
      RABBITMQ_HOST: rabbitmq
      REDIS_HOST: redis
      MINIO_HOST: minio
      MINIO_ACCESS_KEY: minioadmin
      MINIO_SECRET_KEY: minioadmin
    depends_on:
      postgresql:
        condition: service_healthy
      redis:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
      minio-init:
        condition: service_completed_successfully
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8080/actuator/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s

  api-server-2:
    build: ./api-server
    environment:
      POSTGRES_HOST: postgresql
      POSTGRES_DB: videodb
      POSTGRES_USER: videouser
      POSTGRES_PASSWORD: videopass
      RABBITMQ_HOST: rabbitmq
      REDIS_HOST: redis
      MINIO_HOST: minio
      MINIO_ACCESS_KEY: minioadmin
      MINIO_SECRET_KEY: minioadmin
    depends_on:
      postgresql:
        condition: service_healthy
      redis:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
      minio-init:
        condition: service_completed_successfully
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8080/actuator/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s

  transcoding-worker-1:
    build: ./transcoding-worker
    environment:
      RABBITMQ_HOST: rabbitmq
      MINIO_HOST: minio
      MINIO_ACCESS_KEY: minioadmin
      MINIO_SECRET_KEY: minioadmin
    depends_on:
      rabbitmq:
        condition: service_healthy
      minio-init:
        condition: service_completed_successfully
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8082/actuator/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s

  transcoding-worker-2:
    build: ./transcoding-worker
    environment:
      RABBITMQ_HOST: rabbitmq
      MINIO_HOST: minio
      MINIO_ACCESS_KEY: minioadmin
      MINIO_SECRET_KEY: minioadmin
    depends_on:
      rabbitmq:
        condition: service_healthy
      minio-init:
        condition: service_completed_successfully
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8082/actuator/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s

  completion-handler-1:
    build: ./completion-handler
    environment:
      POSTGRES_HOST: postgresql
      POSTGRES_DB: videodb
      POSTGRES_USER: videouser
      POSTGRES_PASSWORD: videopass
      RABBITMQ_HOST: rabbitmq
      REDIS_HOST: redis
    depends_on:
      postgresql:
        condition: service_healthy
      redis:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8083/actuator/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s

  completion-handler-2:
    build: ./completion-handler
    environment:
      POSTGRES_HOST: postgresql
      POSTGRES_DB: videodb
      POSTGRES_USER: videouser
      POSTGRES_PASSWORD: videopass
      RABBITMQ_HOST: rabbitmq
      REDIS_HOST: redis
    depends_on:
      postgresql:
        condition: service_healthy
      redis:
        condition: service_healthy
      rabbitmq:
        condition: service_healthy
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost:8083/actuator/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 5
      start_period: 30s

  # --- Load Balancer ---
  nginx:
    image: nginx:alpine
    ports:
      - "8080:80"
    volumes:
      - ./nginx/nginx.conf:/etc/nginx/conf.d/default.conf:ro
    depends_on:
      api-server-1:
        condition: service_healthy
      api-server-2:
        condition: service_healthy
    healthcheck:
      test: ["CMD-SHELL", "wget -qO- http://localhost/health || exit 1"]
      interval: 10s
      timeout: 5s
      retries: 3

  # --- Monitoring ---
  prometheus:
    image: prom/prometheus
    ports:
      - "9090:9090"
    volumes:
      - ./prometheus/prometheus.yml:/etc/prometheus/prometheus.yml:ro
    depends_on:
      - api-server-1
      - api-server-2
      - transcoding-worker-1
      - transcoding-worker-2
      - completion-handler-1
      - completion-handler-2

  grafana:
    image: grafana/grafana
    ports:
      - "3000:3000"
    environment:
      GF_SECURITY_ADMIN_PASSWORD: admin
      GF_SECURITY_ADMIN_USER: admin
    volumes:
      - ./grafana/provisioning:/etc/grafana/provisioning:ro
      - ./grafana/dashboards:/var/lib/grafana/dashboards:ro
    depends_on:
      - prometheus

  # --- Load Test (profile: test) ---
  load-client:
    build: ./load-client
    profiles:
      - test
    volumes:
      - ./video:/videos:ro
    environment:
      API_BASE_URL: http://nginx
      VIDEO_DIR: /videos
    depends_on:
      nginx:
        condition: service_healthy

volumes:
  postgres-data:
  minio-data:
```

- [ ] **Step 6: Commit**

```bash
git add api-server/Dockerfile transcoding-worker/Dockerfile completion-handler/Dockerfile load-client/Dockerfile docker-compose.yml
git commit -m "feat: add Dockerfiles and docker-compose.yml with all services"
```

---

### Task 9: Load Test Client

**Files:**
- Create: `load-client/build.gradle`
- Create: `load-client/src/main/java/com/videosharing/loadclient/LoadClientApplication.java`
- Create: `load-client/src/main/java/com/videosharing/loadclient/VideoUploader.java`
- Create: `load-client/src/main/java/com/videosharing/loadclient/MetadataPoller.java`
- Create: `load-client/src/main/java/com/videosharing/loadclient/VideoStreamer.java`
- Create: `load-client/src/main/java/com/videosharing/loadclient/LoadTestReport.java`

- [ ] **Step 1: Create `load-client/build.gradle`**

```groovy
plugins {
    id 'org.springframework.boot'
    id 'io.spring.dependency-management'
}

dependencies {
    implementation 'com.fasterxml.jackson.core:jackson-databind'
    implementation 'com.fasterxml.jackson.datatype:jackson-datatype-jsr310'
    implementation 'org.springframework.boot:spring-boot-starter'
}
```

- [ ] **Step 2: Create `VideoUploader.java`**

```java
package com.videosharing.loadclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.*;

public class VideoUploader {

    private final HttpClient client;
    private final String baseUrl;
    private final ObjectMapper mapper;

    public record UploadResult(String filename, String videoId, double durationMs, String error) {}

    public VideoUploader(HttpClient client, String baseUrl, ObjectMapper mapper) {
        this.client = client;
        this.baseUrl = baseUrl;
        this.mapper = mapper;
    }

    public List<UploadResult> uploadAll(List<Path> videoFiles) throws Exception {
        ExecutorService executor = Executors.newFixedThreadPool(videoFiles.size());
        List<Future<UploadResult>> futures = new ArrayList<>();
        for (Path videoFile : videoFiles) {
            futures.add(executor.submit(() -> uploadSingle(videoFile)));
        }

        List<UploadResult> results = new ArrayList<>();
        for (Future<UploadResult> f : futures) {
            results.add(f.get(5, TimeUnit.MINUTES));
        }
        executor.shutdown();
        return results;
    }

    private UploadResult uploadSingle(Path videoFile) {
        String filename = videoFile.getFileName().toString();
        Instant start = Instant.now();
        try {
            String boundary = "----FormBoundary" + UUID.randomUUID().toString().replace("-", "");
            byte[] fileBytes = Files.readAllBytes(videoFile);
            byte[] body = buildMultipartBody(boundary, filename, fileBytes);

            HttpRequest req = HttpRequest.newBuilder()
                    .uri(URI.create(baseUrl + "/api/videos/upload"))
                    .timeout(Duration.ofMinutes(5))
                    .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                    .build();

            HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
            double durationMs = Duration.between(start, Instant.now()).toMillis();

            if (resp.statusCode() == 202) {
                JsonNode json = mapper.readTree(resp.body());
                return new UploadResult(filename, json.get("videoId").asText(), durationMs, null);
            } else {
                return new UploadResult(filename, null, durationMs, "HTTP " + resp.statusCode() + ": " + resp.body());
            }
        } catch (Exception e) {
            double durationMs = Duration.between(start, Instant.now()).toMillis();
            return new UploadResult(filename, null, durationMs, e.getMessage());
        }
    }

    private byte[] buildMultipartBody(String boundary, String filename, byte[] fileBytes) throws Exception {
        var baos = new ByteArrayOutputStream();
        String crlf = "\r\n";
        baos.write(("--" + boundary + crlf).getBytes());
        baos.write(("Content-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"" + crlf).getBytes());
        baos.write(("Content-Type: video/mp4" + crlf).getBytes());
        baos.write(crlf.getBytes());
        baos.write(fileBytes);
        baos.write((crlf + "--" + boundary + "--" + crlf).getBytes());
        return baos.toByteArray();
    }
}
```

- [ ] **Step 3: Create `MetadataPoller.java`**

```java
package com.videosharing.loadclient;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class MetadataPoller {

    private final HttpClient client;
    private final String baseUrl;
    private final ObjectMapper mapper;
    private static final int MAX_POLL_SECONDS = 600;

    public MetadataPoller(HttpClient client, String baseUrl, ObjectMapper mapper) {
        this.client = client;
        this.baseUrl = baseUrl;
        this.mapper = mapper;
    }

    public record PollResult(Map<String, String> statuses, double waitSeconds) {}

    public PollResult pollUntilComplete(List<VideoUploader.UploadResult> uploads) throws Exception {
        Instant pollStart = Instant.now();
        Map<String, String> statuses = new HashMap<>();

        while (true) {
            boolean allDone = true;
            for (VideoUploader.UploadResult r : uploads) {
                String current = statuses.get(r.videoId());
                if ("COMPLETED".equals(current) || "FAILED".equals(current)) continue;

                HttpRequest req = HttpRequest.newBuilder()
                        .uri(URI.create(baseUrl + "/api/videos/" + r.videoId()))
                        .timeout(Duration.ofSeconds(10))
                        .GET().build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());

                if (resp.statusCode() == 200) {
                    JsonNode body = mapper.readTree(resp.body());
                    String status = body.get("status").asText();
                    statuses.put(r.videoId(), status);
                    if (!"COMPLETED".equals(status) && !"FAILED".equals(status)) {
                        allDone = false;
                    }
                } else {
                    allDone = false;
                }
            }

            if (allDone) break;
            if (Duration.between(pollStart, Instant.now()).toSeconds() > MAX_POLL_SECONDS) {
                System.out.println("Timeout waiting for transcoding!");
                break;
            }
            Thread.sleep(3000);
            System.out.print(".");
        }

        double waitSec = Duration.between(pollStart, Instant.now()).toMillis() / 1000.0;
        return new PollResult(statuses, waitSec);
    }
}
```

- [ ] **Step 4: Create `VideoStreamer.java`**

```java
package com.videosharing.loadclient;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public class VideoStreamer {

    private static final String[] RESOLUTIONS = {"144p", "360p", "720p"};
    private final HttpClient client;
    private final String baseUrl;

    public record StreamResult(int success, int failure, List<Double> timesMs) {}

    public VideoStreamer(HttpClient client, String baseUrl) {
        this.client = client;
        this.baseUrl = baseUrl;
    }

    public StreamResult streamAll(List<VideoUploader.UploadResult> uploads, Map<String, String> statuses) {
        int success = 0, failure = 0;
        List<Double> times = new ArrayList<>();

        for (VideoUploader.UploadResult r : uploads) {
            if (!"COMPLETED".equals(statuses.get(r.videoId()))) continue;

            for (String resolution : RESOLUTIONS) {
                Instant start = Instant.now();
                try {
                    HttpRequest req = HttpRequest.newBuilder()
                            .uri(URI.create(baseUrl + "/api/videos/stream?file=" + r.filename() + "&resolution=" + resolution))
                            .timeout(Duration.ofSeconds(60))
                            .GET().build();
                    HttpResponse<byte[]> resp = client.send(req, HttpResponse.BodyHandlers.ofByteArray());

                    double ms = Duration.between(start, Instant.now()).toMillis();
                    if (resp.statusCode() == 200 && resp.body().length > 0) {
                        success++;
                        times.add(ms);
                        System.out.printf("  PASS %s@%s: %d bytes (%.2fs)%n",
                                r.filename(), resolution, resp.body().length, ms / 1000.0);
                    } else {
                        failure++;
                        System.out.printf("  FAIL %s@%s: HTTP %d%n", r.filename(), resolution, resp.statusCode());
                    }
                } catch (Exception e) {
                    failure++;
                    System.out.printf("  FAIL %s@%s: %s%n", r.filename(), resolution, e.getMessage());
                }
            }
        }
        return new StreamResult(success, failure, times);
    }
}
```

- [ ] **Step 5: Create `LoadTestReport.java`**

```java
package com.videosharing.loadclient;

import java.util.List;

public class LoadTestReport {

    public static void print(List<VideoUploader.UploadResult> successful,
                             List<VideoUploader.UploadResult> failed,
                             double transcodingWaitSec,
                             VideoStreamer.StreamResult streamResult) {
        System.out.println("\n=== LOAD TEST SUMMARY ===");
        System.out.println("Uploads: " + successful.size() + " OK, " + failed.size() + " FAILED");
        double avgUpload = successful.stream().mapToDouble(VideoUploader.UploadResult::durationMs).average().orElse(0);
        System.out.printf("Avg upload time: %.2fs%n", avgUpload / 1000.0);
        System.out.printf("Transcoding wait: %.2fs%n", transcodingWaitSec);
        System.out.println("Streams: " + streamResult.success() + " OK, " + streamResult.failure() + " FAILED");
        double avgStream = streamResult.timesMs().stream().mapToDouble(d -> d).average().orElse(0);
        System.out.printf("Avg stream time: %.2fs%n", avgStream / 1000.0);
        System.out.println("========================");
    }
}
```

- [ ] **Step 6: Create `LoadClientApplication.java`**

```java
package com.videosharing.loadclient;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

@SpringBootApplication
public class LoadClientApplication implements CommandLineRunner {

    public static void main(String[] args) {
        SpringApplication.run(LoadClientApplication.class, args);
    }

    @Override
    public void run(String... args) throws Exception {
        String baseUrl = System.getenv().getOrDefault("API_BASE_URL", "http://localhost:8080");
        String videoDir = System.getenv().getOrDefault("VIDEO_DIR", "./video");
        ObjectMapper mapper = new ObjectMapper();
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

        // Discover mp4 files
        List<Path> videoFiles;
        try (var stream = Files.list(Path.of(videoDir))) {
            videoFiles = stream.filter(p -> p.toString().endsWith(".mp4")).toList();
        }
        System.out.println("Found " + videoFiles.size() + " video files to upload");

        // Phase 1: Upload
        System.out.println("\n=== UPLOADING ===");
        VideoUploader uploader = new VideoUploader(client, baseUrl, mapper);
        List<VideoUploader.UploadResult> results = uploader.uploadAll(videoFiles);

        List<VideoUploader.UploadResult> successful = results.stream().filter(r -> r.videoId() != null).toList();
        List<VideoUploader.UploadResult> failed = results.stream().filter(r -> r.videoId() == null).toList();

        for (VideoUploader.UploadResult r : successful) {
            System.out.printf("  %s -> %s (%.2fs)%n", r.filename(), r.videoId(), r.durationMs() / 1000.0);
        }
        for (VideoUploader.UploadResult r : failed) {
            System.out.printf("  %s -> FAILED: %s%n", r.filename(), r.error());
        }

        if (successful.isEmpty()) {
            System.out.println("No successful uploads. Exiting.");
            return;
        }

        // Phase 2: Poll for transcoding completion
        System.out.println("\n=== WAITING FOR TRANSCODING ===");
        MetadataPoller poller = new MetadataPoller(client, baseUrl, mapper);
        MetadataPoller.PollResult pollResult = poller.pollUntilComplete(successful);
        System.out.printf("\nTranscoding wait: %.2fs%n", pollResult.waitSeconds());

        // Phase 3: Stream verification
        System.out.println("\n=== STREAMING TEST ===");
        VideoStreamer streamer = new VideoStreamer(client, baseUrl);
        VideoStreamer.StreamResult streamResult = streamer.streamAll(successful, pollResult.statuses());

        // Report
        LoadTestReport.print(successful, failed, pollResult.waitSeconds(), streamResult);
    }
}
```

- [ ] **Step 7: Verify build compiles**

Run: `./gradlew :load-client:build -x test`
Expected: BUILD SUCCESSFUL

- [ ] **Step 8: Commit**

```bash
git add load-client/
git commit -m "feat: add load test client with upload, poll, and streaming scenarios"
```

---

### Task 10: Components Diagram & README

**Files:**
- Create: `components-diagram.drawio`
- Create: `README.md`

- [ ] **Step 1: Create `components-diagram.drawio`**

Draw.io XML showing:
- Client → Nginx (Load Balancer) → API Server 1 & 2
- API Servers → MinIO (Original Storage)
- API Servers → RabbitMQ (transcoding-tasks queue)
- API Servers → PostgreSQL & Redis (metadata read)
- API Servers → MinIO (Transcoded Storage, for streaming)
- RabbitMQ → Transcoding Worker 1 & 2
- Transcoding Workers → MinIO (read original, write transcoded)
- Transcoding Workers → RabbitMQ (transcoding-completions queue)
- RabbitMQ → Completion Handler 1 & 2
- Completion Handlers → PostgreSQL & Redis
- Prometheus → all services
- Grafana → Prometheus

- [ ] **Step 2: Create `README.md`**

Cover:
- Project overview
- Architecture diagram reference
- Prerequisites (Docker, Docker Compose)
- Quick start: `docker compose up --build`
- Running load tests: `docker compose --profile test up load-client`
- Accessing services: Nginx (8080), Grafana (3000), RabbitMQ Management (15672), MinIO Console (9001), Prometheus (9090)
- API endpoints reference
- Project structure
- Development (Gradle build commands)

- [ ] **Step 3: Commit**

```bash
git add components-diagram.drawio README.md
git commit -m "docs: add components diagram and README"
```

---

### Task 11: Build, Run & Integration Test

- [ ] **Step 1: Build all JARs**

Run: `./gradlew clean build -x test`
Expected: BUILD SUCCESSFUL for all modules

- [ ] **Step 2: Run unit tests**

Run: `./gradlew test`
Expected: All tests pass

- [ ] **Step 3: Build and start docker compose**

Run: `docker compose up --build -d`
Wait for all services to be healthy: `docker compose ps`
Expected: All services running and healthy

- [ ] **Step 4: Verify API health**

Run: `curl http://localhost:8080/actuator/health`
Expected: `{"status":"UP"}`

- [ ] **Step 5: Run load test**

Run: `docker compose --profile test up load-client`
Expected: All uploads succeed, all transcoding completes, all streams return HTTP 200

- [ ] **Step 6: Verify Grafana dashboards**

Open `http://localhost:3000` (admin/admin), verify the Video Platform dashboard shows metrics for API requests, transcoding jobs, and JVM stats.

- [ ] **Step 7: Fix any issues found during integration testing**

If any step fails, diagnose and fix. Re-run from Step 3.

- [ ] **Step 8: Final commit if fixes were needed**

```bash
git add -A
git commit -m "fix: address issues found during integration testing"
```
