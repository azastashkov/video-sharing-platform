# Video Sharing Platform

A scalable video sharing platform with upload, transcoding (144p/360p/720p), and streaming — built with microservices behind Nginx with full monitoring.

## Architecture

The platform is composed of three Spring Boot microservices, each running as two replicas behind an Nginx load balancer:

- **api-server**: Handles video uploads, metadata queries, and streaming. Publishes transcoding tasks to RabbitMQ and stores originals in MinIO.
- **transcoding-worker**: Consumes transcoding tasks from RabbitMQ, uses FFmpeg to produce 144p/360p/720p variants, stores results in MinIO, and publishes completion events.
- **completion-handler**: Consumes transcoding completion events, updates video metadata in PostgreSQL, and invalidates/populates the Redis cache.

See `components-diagram.drawio` for the full architecture diagram (open with [draw.io](https://app.diagrams.net)).

**Infrastructure:**
- **Nginx** — load balancer for api-server replicas
- **MinIO** — S3-compatible object storage for originals and transcoded files
- **RabbitMQ** — async messaging between services
- **PostgreSQL** — video metadata persistence
- **Redis** — metadata caching
- **Prometheus + Grafana** — metrics scraping and dashboards

## Prerequisites

- [Docker](https://docs.docker.com/get-docker/) 24+
- [Docker Compose](https://docs.docker.com/compose/install/) v2.20+
- Java 21 + Gradle (for local development only)

## Quick Start

Build all service JARs and start the full stack:

```bash
./gradlew clean build -x test
docker compose up --build -d
```

Wait for all services to become healthy:

```bash
docker compose ps
```

Verify the API is up:

```bash
curl http://localhost:8080/actuator/health
# {"status":"UP"}
```

## Running Load Tests

Place one or more `.mp4` files in the `./video/` directory, then run:

```bash
docker compose --profile test up load-client
```

The load client will:
1. Upload all `.mp4` files concurrently
2. Poll until transcoding is complete (up to 10 minutes)
3. Verify streaming at 144p/360p/720p for each completed video
4. Print a summary report

## Service URLs

| Service | URL | Credentials |
|---|---|---|
| API (via Nginx) | http://localhost:8080 | — |
| Grafana | http://localhost:3000 | admin / admin |
| RabbitMQ Management | http://localhost:15672 | guest / guest |
| MinIO Console | http://localhost:9001 | minioadmin / minioadmin |
| Prometheus | http://localhost:9090 | — |

## API Endpoints

| Method | Path | Description |
|---|---|---|
| POST | `/api/videos/upload` | Upload a video file (multipart/form-data, field: `file`) |
| GET | `/api/videos/{videoId}` | Get video metadata and transcoding status |
| GET | `/api/videos/stream?file={filename}&resolution={res}` | Stream a transcoded video (res: 144p, 360p, 720p) |
| GET | `/actuator/health` | Health check |
| GET | `/actuator/prometheus` | Prometheus metrics |

### Upload Response (HTTP 202)

```json
{
  "videoId": "550e8400-e29b-41d4-a716-446655440000",
  "status": "UPLOADING"
}
```

### Video Metadata Response (HTTP 200)

```json
{
  "id": "550e8400-e29b-41d4-a716-446655440000",
  "originalFilename": "sample.mp4",
  "status": "COMPLETED",
  "transcodedFiles": [
    { "resolution": "144p", "url": "/api/videos/stream?file=sample.mp4&resolution=144p", "fileSize": 1048576 },
    { "resolution": "360p", "url": "/api/videos/stream?file=sample.mp4&resolution=360p", "fileSize": 5242880 },
    { "resolution": "720p", "url": "/api/videos/stream?file=sample.mp4&resolution=720p", "fileSize": 20971520 }
  ],
  "createdAt": "2026-03-22T10:00:00Z"
}
```

## Project Structure

```
video-sharing-platform/
├── api-server/                    # Upload, metadata, streaming service
│   ├── src/main/java/
│   ├── src/main/resources/
│   │   ├── application.yml
│   │   └── schema.sql
│   └── Dockerfile
├── transcoding-worker/            # FFmpeg transcoding service
│   ├── src/main/java/
│   └── Dockerfile
├── completion-handler/            # Completion event consumer
│   ├── src/main/java/
│   └── Dockerfile
├── load-client/                   # Java load test client
│   ├── src/main/java/
│   └── Dockerfile
├── common/                        # Shared DTOs (TranscodingTask, etc.)
├── nginx/
│   └── nginx.conf                 # Upstream + proxy config
├── prometheus/
│   └── prometheus.yml             # Scrape configs for all services
├── grafana/
│   ├── provisioning/              # Auto-provision datasource + dashboard
│   └── dashboards/
│       └── video-platform.json    # Pre-built Grafana dashboard
├── components-diagram.drawio      # Architecture diagram
├── docker-compose.yml             # Full stack orchestration
├── build.gradle                   # Root Gradle build
└── settings.gradle                # Module declarations
```

## Development

Build a specific module:

```bash
./gradlew :api-server:build -x test
./gradlew :transcoding-worker:build -x test
./gradlew :completion-handler:build -x test
./gradlew :load-client:build -x test
```

Build and test everything:

```bash
./gradlew clean build
```

Build all JARs without tests (for Docker builds):

```bash
./gradlew clean build -x test
```

Run tests only:

```bash
./gradlew test
```

Rebuild and restart a single service:

```bash
./gradlew :api-server:build -x test
docker compose up --build api-server-1 api-server-2 -d
```

View logs for a service:

```bash
docker compose logs -f api-server-1
docker compose logs -f transcoding-worker-1
```
