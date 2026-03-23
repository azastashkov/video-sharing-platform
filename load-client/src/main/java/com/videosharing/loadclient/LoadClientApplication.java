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
