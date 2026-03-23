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
