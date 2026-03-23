# Video Sharing Platform — Design Spec

## Overview

A video sharing platform built with Java 21, Spring Boot, and Groovy Gradle. Users upload mp4 files which are transcoded to multiple resolutions and made available for streaming. The system is designed for high scalability and availability using a microservices architecture with multiple replicas behind a load balancer.

## Architecture

### Services (2 replicas each)

#### api-server
- **Upload endpoint**: `POST /api/videos/upload` — accepts multipart mp4, stores to MinIO `original-storage` bucket with key `originals/{videoId}/{filename}`, publishes transcoding task to RabbitMQ, returns `202 Accepted` with videoId.
- **Metadata endpoint**: `GET /api/videos/{videoId}` — returns video metadata including URLs for each transcoded resolution. Reads from Redis cache first, falls through to PostgreSQL on cache miss.
- **Streaming endpoint**: `GET /api/videos/stream?file={filename}&resolution={resolution}` — streams the transcoded mp4 file from MinIO `transcoded-storage` bucket. The URL format per the requirement: `http://host:[port]/api/videos/stream?file=filename&resolution=XXXp`
- **List endpoint**: `GET /api/videos` — lists all videos with their status.
- Exposes `/actuator/prometheus` and `/actuator/health`.

#### transcoding-worker
- Consumes messages from RabbitMQ `transcoding-tasks` queue.
- Downloads original mp4 from MinIO `original-storage`.
- Runs FFmpeg to transcode to three resolutions:
  - 144p (256x144)
  - 360p (640x360)
  - 720p (1280x720)
- Uploads transcoded files to MinIO `transcoded-storage` bucket with keys: `{videoId}/{resolution}/{originalFilename}`
- Publishes completion message to RabbitMQ `transcoding-completions` queue containing videoId, original filename, and list of transcoded file references (resolution, minioKey, fileSize).
- On failure: message is nacked and routed to dead letter queue after 3 retries.
- Exposes `/actuator/prometheus` and `/actuator/health`.

#### completion-handler
- Consumes messages from RabbitMQ `transcoding-completions` queue.
- Inserts transcoded file records into PostgreSQL `transcoded_files` table.
- Updates video status to `COMPLETED` in PostgreSQL.
- Updates Redis cache with the new metadata.
- Exposes `/actuator/prometheus` and `/actuator/health`.

### Infrastructure

| Component | Image | Purpose |
|-----------|-------|---------|
| **Nginx** | nginx:alpine | Load balancer for API server replicas. Health checks on `/actuator/health`. Upstream config with 2 API servers. |
| **RabbitMQ** | rabbitmq:3-management | Two work queues + dead letter exchange. Management UI on port 15672. |
| **PostgreSQL** | postgres:16-alpine | Metadata storage. Single instance with Docker volume. |
| **Redis** | redis:7-alpine | Metadata cache with 5-minute TTL. |
| **MinIO** | minio/minio | S3-compatible object storage. Two buckets: `original-storage`, `transcoded-storage`. Console on port 9001. |
| **Prometheus** | prom/prometheus | Scrapes all service instances at `/actuator/prometheus`. |
| **Grafana** | grafana/grafana | Pre-provisioned dashboards. Port 3000. |

### Load Test Client
- Java application using `HttpClient` (Java 21).
- Started via `docker compose --profile test up load-client`.
- Reads all mp4 files from `./video` directory (mounted as volume).
- Scenario:
  1. Upload all videos concurrently.
  2. Poll metadata endpoints until all videos reach `COMPLETED` status.
  3. Stream each transcoded file for each resolution and verify HTTP 200.
  4. Report summary: upload times, transcoding times, streaming throughput, errors.
- Uses multiple threads to simulate concurrent load.

## Database Schema

```sql
CREATE TABLE videos (
    id UUID PRIMARY KEY,
    original_filename VARCHAR(255) NOT NULL,
    original_minio_key VARCHAR(500) NOT NULL,
    status VARCHAR(50) NOT NULL DEFAULT 'UPLOADING',
    created_at TIMESTAMP NOT NULL DEFAULT NOW(),
    updated_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE TABLE transcoded_files (
    id UUID PRIMARY KEY,
    video_id UUID NOT NULL REFERENCES videos(id),
    resolution VARCHAR(10) NOT NULL,
    minio_key VARCHAR(500) NOT NULL,
    file_size BIGINT,
    created_at TIMESTAMP NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_transcoded_files_video_id ON transcoded_files(video_id);
```

Video status transitions: `UPLOADING` → `TRANSCODING` → `COMPLETED` (or `FAILED`).

## Message Schemas

### Transcoding Task (RabbitMQ: `transcoding-tasks`)
```json
{
  "videoId": "uuid",
  "originalFilename": "clouds-blue-sky.mp4",
  "originalMinioKey": "originals/uuid/clouds-blue-sky.mp4"
}
```

### Transcoding Completion (RabbitMQ: `transcoding-completions`)
```json
{
  "videoId": "uuid",
  "originalFilename": "clouds-blue-sky.mp4",
  "transcodedFiles": [
    {"resolution": "144p", "minioKey": "uuid/144p/clouds-blue-sky.mp4", "fileSize": 1234567},
    {"resolution": "360p", "minioKey": "uuid/360p/clouds-blue-sky.mp4", "fileSize": 2345678},
    {"resolution": "720p", "minioKey": "uuid/720p/clouds-blue-sky.mp4", "fileSize": 3456789}
  ]
}
```

## Project Structure

```
video-sharing-platform/
├── common/                          # Shared library
│   ├── build.gradle
│   └── src/main/java/.../common/
│       ├── dto/
│       │   ├── TranscodingTask.java
│       │   ├── TranscodingCompletion.java
│       │   ├── TranscodedFileInfo.java
│       │   └── VideoMetadata.java
│       └── config/
│           ├── RabbitMQConfig.java
│           └── MinioConfig.java
├── api-server/
│   ├── build.gradle
│   ├── Dockerfile
│   └── src/
│       ├── main/java/.../apiserver/
│       │   ├── ApiServerApplication.java
│       │   ├── controller/
│       │   │   └── VideoController.java
│       │   ├── service/
│       │   │   ├── VideoUploadService.java
│       │   │   ├── VideoMetadataService.java
│       │   │   └── VideoStreamingService.java
│       │   ├── repository/
│       │   │   ├── VideoRepository.java
│       │   │   └── TranscodedFileRepository.java
│       │   └── config/
│       │       └── AppConfig.java
│       ├── main/resources/
│       │   └── application.yml
│       └── test/
├── transcoding-worker/
│   ├── build.gradle
│   ├── Dockerfile
│   └── src/
│       ├── main/java/.../transcoding/
│       │   ├── TranscodingWorkerApplication.java
│       │   ├── service/
│       │   │   ├── TranscodingService.java
│       │   │   └── FFmpegService.java
│       │   └── listener/
│       │       └── TranscodingTaskListener.java
│       ├── main/resources/
│       │   └── application.yml
│       └── test/
├── completion-handler/
│   ├── build.gradle
│   ├── Dockerfile
│   └── src/
│       ├── main/java/.../completion/
│       │   ├── CompletionHandlerApplication.java
│       │   ├── service/
│       │   │   └── CompletionService.java
│       │   ├── repository/
│       │   │   ├── VideoRepository.java
│       │   │   └── TranscodedFileRepository.java
│       │   └── listener/
│       │       └── CompletionListener.java
│       ├── main/resources/
│       │   └── application.yml
│       └── test/
├── load-client/
│   ├── build.gradle
│   ├── Dockerfile
│   └── src/main/java/.../loadclient/
│       ├── LoadClientApplication.java
│       ├── VideoUploader.java
│       ├── MetadataPoller.java
│       ├── VideoStreamer.java
│       └── LoadTestReport.java
├── nginx/
│   └── nginx.conf
├── prometheus/
│   └── prometheus.yml
├── grafana/
│   ├── provisioning/
│   │   ├── datasources/
│   │   │   └── prometheus.yml
│   │   └── dashboards/
│   │       └── dashboard.yml
│   └── dashboards/
│       └── video-platform.json
├── docker-compose.yml
├── build.gradle                     # Root build
├── settings.gradle
├── components-diagram.drawio
├── video/                           # Existing sample mp4s
└── README.md
```

## Nginx Configuration

```nginx
upstream api_servers {
    server api-server-1:8080 max_fails=3 fail_timeout=30s;
    server api-server-2:8080 max_fails=3 fail_timeout=30s;
}

server {
    listen 80;
    client_max_body_size 500M;

    location /api/ {
        proxy_pass http://api_servers;
        proxy_connect_timeout 5s;
        proxy_read_timeout 300s;  # Long timeout for uploads/streaming
    }

    location /actuator/health {
        proxy_pass http://api_servers;
    }
}
```

Health check: Nginx passive health checks — after 3 failed requests within 30s, the upstream server is marked unavailable for 30s. Docker-level healthchecks on api-server containers provide additional resilience.

## Resilience

- **Dead letter queues**: Failed transcoding messages go to `transcoding-tasks-dlq` after 3 attempts. Failed completion messages go to `transcoding-completions-dlq` after 3 attempts.
- **Retry with backoff**: RabbitMQ retry via `spring-retry` with exponential backoff (1s, 2s, 4s).
- **Health checks**: Docker healthchecks for PostgreSQL (`pg_isready`), Redis (`redis-cli ping`), RabbitMQ (`rabbitmq-diagnostics -q ping`), MinIO (`mc ready`).
- **Startup dependencies**: Services wait for infrastructure via `depends_on` with `condition: service_healthy`.
- **Cache miss fallthrough**: If Redis is unavailable, API server reads directly from PostgreSQL.
- **Nginx failover**: Automatically routes around unhealthy API server instances.

## Monitoring

### Prometheus Targets
All Spring Boot services expose `/actuator/prometheus` with Micrometer metrics:
- `http_server_requests` — request count, duration histograms
- `rabbitmq_consumed`, `rabbitmq_published` — message counts
- `jvm_memory_used`, `jvm_gc_pause` — JVM metrics
- Custom metrics:
  - `video_upload_total` — counter of uploads
  - `video_upload_size_bytes` — histogram of upload sizes
  - `transcoding_duration_seconds` — histogram of transcoding time per resolution
  - `transcoding_jobs_active` — gauge of in-progress transcoding jobs

### Grafana Dashboards
Pre-provisioned dashboard with panels:
- **API Overview**: Request rate, latency p50/p95/p99, error rate, active connections
- **Upload Metrics**: Upload count, average size, upload duration
- **Transcoding**: Queue depth, active jobs, processing time by resolution, success/failure rate
- **Streaming**: Stream request rate, bytes served, latency
- **Infrastructure**: RabbitMQ queue depths, PostgreSQL connections, Redis hit/miss ratio
- **JVM**: Heap usage, GC pauses, thread count — per service instance

## Docker Compose Structure

```yaml
services:
  # Infrastructure
  postgresql:     # port 5432, healthcheck pg_isready
  redis:          # port 6379, healthcheck redis-cli ping
  rabbitmq:       # ports 5672, 15672, healthcheck rabbitmq-diagnostics
  minio:          # ports 9000, 9001, healthcheck mc ready
  minio-init:     # one-shot: creates buckets

  # Application (2 replicas each)
  api-server-1:       # port 8080 internal
  api-server-2:       # port 8080 internal (separate container, same port)
  transcoding-worker-1:
  transcoding-worker-2:
  completion-handler-1:
  completion-handler-2:

  # Load Balancer
  nginx:          # port 80 → external 8080

  # Monitoring
  prometheus:     # port 9090
  grafana:        # port 3000

  # Testing (profile: test)
  load-client:    # profile "test", depends on nginx
```

## Unit Tests

Each service includes unit tests:
- **api-server**: Test VideoController (MockMvc), VideoUploadService (mock MinIO + RabbitMQ), VideoMetadataService (mock Redis + repository), VideoStreamingService (mock MinIO).
- **transcoding-worker**: Test FFmpegService (mock process execution), TranscodingService (mock MinIO + RabbitMQ), TranscodingTaskListener (integration with mocked dependencies).
- **completion-handler**: Test CompletionService (mock repositories + Redis), CompletionListener (message deserialization + service invocation).

## Technology Stack

| Technology | Version | Purpose |
|------------|---------|---------|
| Java | 21 | Language runtime |
| Spring Boot | 3.3.x | Application framework |
| Gradle (Groovy) | 8.x | Build system |
| Spring AMQP | — | RabbitMQ integration |
| Spring Data JPA | — | PostgreSQL access |
| Spring Data Redis | — | Redis cache access |
| MinIO Java SDK | — | S3-compatible storage client |
| Micrometer | — | Prometheus metrics |
| FFmpeg | 6.x | Video transcoding |
| JUnit 5 + Mockito | — | Unit testing |
| Nginx | alpine | Load balancing |
