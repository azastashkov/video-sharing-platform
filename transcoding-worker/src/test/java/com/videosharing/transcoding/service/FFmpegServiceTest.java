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
