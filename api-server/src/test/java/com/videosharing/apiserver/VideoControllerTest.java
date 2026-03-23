package com.videosharing.apiserver;

import com.videosharing.apiserver.controller.VideoController;
import com.videosharing.apiserver.service.VideoMetadataService;
import com.videosharing.apiserver.service.VideoStreamingService;
import com.videosharing.apiserver.service.VideoUploadService;
import com.videosharing.common.dto.VideoMetadata;
import com.videosharing.common.dto.VideoStatus;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.core.io.InputStreamResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import java.io.ByteArrayInputStream;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(VideoController.class)
class VideoControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private VideoUploadService uploadService;

    @MockBean
    private VideoMetadataService metadataService;

    @MockBean
    private VideoStreamingService streamingService;

    @Test
    void uploadVideo_returns202() throws Exception {
        UUID videoId = UUID.randomUUID();
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.mp4", "video/mp4", "video content".getBytes()
        );

        when(uploadService.uploadVideo(any())).thenReturn(videoId);

        mockMvc.perform(multipart("/api/videos/upload").file(file))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.videoId").value(videoId.toString()))
                .andExpect(jsonPath("$.status").value("TRANSCODING"));
    }

    @Test
    void uploadVideo_emptyFile_returns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.mp4", "video/mp4", new byte[0]
        );

        mockMvc.perform(multipart("/api/videos/upload").file(file))
                .andExpect(status().isBadRequest());
    }

    @Test
    void uploadVideo_nonMp4_returns400() throws Exception {
        MockMultipartFile file = new MockMultipartFile(
                "file", "test.avi", "video/avi", "video content".getBytes()
        );

        mockMvc.perform(multipart("/api/videos/upload").file(file))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getVideoMetadata_returns200() throws Exception {
        UUID videoId = UUID.randomUUID();
        VideoMetadata metadata = new VideoMetadata(
                videoId, "video.mp4", VideoStatus.COMPLETED, List.of(), Instant.now()
        );

        when(metadataService.getVideoMetadata(eq(videoId), anyString())).thenReturn(Optional.of(metadata));

        mockMvc.perform(get("/api/videos/{videoId}", videoId))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(videoId.toString()));
    }

    @Test
    void getVideoMetadata_notFound_returns404() throws Exception {
        UUID videoId = UUID.randomUUID();
        when(metadataService.getVideoMetadata(eq(videoId), anyString())).thenReturn(Optional.empty());

        mockMvc.perform(get("/api/videos/{videoId}", videoId))
                .andExpect(status().isNotFound());
    }

    @Test
    void streamVideo_returns200() throws Exception {
        byte[] videoBytes = "video data".getBytes();
        VideoStreamingService.StreamResult streamResult = new VideoStreamingService.StreamResult(
                new InputStreamResource(new ByteArrayInputStream(videoBytes)),
                videoBytes.length,
                "video/mp4"
        );

        when(streamingService.streamVideo("video.mp4", "1080p")).thenReturn(Optional.of(streamResult));

        mockMvc.perform(get("/api/videos/stream")
                        .param("file", "video.mp4")
                        .param("resolution", "1080p"))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "video/mp4"))
                .andExpect(header().exists("Content-Disposition"));
    }

    @Test
    void listVideos_returns200() throws Exception {
        UUID videoId = UUID.randomUUID();
        VideoMetadata metadata = new VideoMetadata(
                videoId, "video.mp4", VideoStatus.COMPLETED, List.of(), Instant.now()
        );

        when(metadataService.listAllVideos(anyString())).thenReturn(List.of(metadata));

        mockMvc.perform(get("/api/videos"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].id").value(videoId.toString()));
    }
}
