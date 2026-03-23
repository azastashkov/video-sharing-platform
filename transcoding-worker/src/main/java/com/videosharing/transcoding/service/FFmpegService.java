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
