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
