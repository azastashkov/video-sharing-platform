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
