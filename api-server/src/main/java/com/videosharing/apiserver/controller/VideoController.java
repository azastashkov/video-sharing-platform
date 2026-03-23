package com.videosharing.apiserver.controller;

import com.videosharing.apiserver.service.VideoMetadataService;
import com.videosharing.apiserver.service.VideoStreamingService;
import com.videosharing.apiserver.service.VideoUploadService;
import com.videosharing.common.dto.VideoMetadata;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@RestController
@RequestMapping("/api/videos")
public class VideoController {

    private final VideoUploadService uploadService;
    private final VideoMetadataService metadataService;
    private final VideoStreamingService streamingService;

    public VideoController(
            VideoUploadService uploadService,
            VideoMetadataService metadataService,
            VideoStreamingService streamingService) {
        this.uploadService = uploadService;
        this.metadataService = metadataService;
        this.streamingService = streamingService;
    }

    @PostMapping("/upload")
    public ResponseEntity<?> uploadVideo(
            @RequestParam("file") MultipartFile file,
            HttpServletRequest request) {

        if (file == null || file.isEmpty()) {
            return ResponseEntity.badRequest().body(Map.of("error", "File must not be empty"));
        }

        String filename = file.getOriginalFilename();
        if (filename == null || !filename.toLowerCase().endsWith(".mp4")) {
            return ResponseEntity.badRequest().body(Map.of("error", "Only .mp4 files are supported"));
        }

        try {
            UUID videoId = uploadService.uploadVideo(file);
            return ResponseEntity.status(HttpStatus.ACCEPTED)
                    .body(Map.of("videoId", videoId, "status", "TRANSCODING"));
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("error", "Upload failed: " + e.getMessage()));
        }
    }

    @GetMapping("/{videoId}")
    public ResponseEntity<VideoMetadata> getVideoMetadata(
            @PathVariable UUID videoId,
            HttpServletRequest request) {

        String baseUrl = getBaseUrl(request);
        Optional<VideoMetadata> metadata = metadataService.getVideoMetadata(videoId, baseUrl);
        return metadata.map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping
    public ResponseEntity<List<VideoMetadata>> listVideos(HttpServletRequest request) {
        String baseUrl = getBaseUrl(request);
        List<VideoMetadata> videos = metadataService.listAllVideos(baseUrl);
        return ResponseEntity.ok(videos);
    }

    @GetMapping("/stream")
    public ResponseEntity<?> streamVideo(
            @RequestParam("file") String file,
            @RequestParam("resolution") String resolution) {

        Optional<VideoStreamingService.StreamResult> result = streamingService.streamVideo(file, resolution);
        if (result.isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        VideoStreamingService.StreamResult streamResult = result.get();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_TYPE, streamResult.contentType())
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"" + file + "\"")
                .header(HttpHeaders.CONTENT_LENGTH, String.valueOf(streamResult.contentLength()))
                .body(streamResult.resource());
    }

    private String getBaseUrl(HttpServletRequest request) {
        String scheme = request.getScheme();
        String serverName = request.getServerName();
        int serverPort = request.getServerPort();
        if ((scheme.equals("http") && serverPort == 80) || (scheme.equals("https") && serverPort == 443)) {
            return scheme + "://" + serverName;
        }
        return scheme + "://" + serverName + ":" + serverPort;
    }
}
