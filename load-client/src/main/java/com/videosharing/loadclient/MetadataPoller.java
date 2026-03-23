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
