package com.videosharing.completion.service;

import com.videosharing.common.dto.TranscodedFileInfo;
import com.videosharing.common.dto.TranscodingCompletion;
import com.videosharing.common.dto.VideoStatus;
import com.videosharing.completion.entity.VideoEntity;
import com.videosharing.completion.entity.TranscodedFileEntity;
import com.videosharing.completion.repository.TranscodedFileRepository;
import com.videosharing.completion.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompletionServiceTest {

    @Mock private VideoRepository videoRepository;
    @Mock private TranscodedFileRepository transcodedFileRepository;
    @Mock private StringRedisTemplate redisTemplate;

    private CompletionService completionService;

    @BeforeEach
    void setUp() {
        completionService = new CompletionService(videoRepository, transcodedFileRepository, redisTemplate);
    }

    @Test
    void handleCompletion_savesTranscodedFilesAndUpdatesStatus() {
        UUID videoId = UUID.randomUUID();
        VideoEntity video = new VideoEntity(videoId, "test.mp4", "originals/" + videoId + "/test.mp4");
        video.setStatus(VideoStatus.TRANSCODING);

        when(videoRepository.findById(videoId)).thenReturn(Optional.of(video));
        when(transcodedFileRepository.save(any())).thenAnswer(i -> i.getArgument(0));
        when(videoRepository.save(any())).thenAnswer(i -> i.getArgument(0));

        TranscodingCompletion completion = new TranscodingCompletion(videoId, "test.mp4", List.of(
                new TranscodedFileInfo("144p", videoId + "/144p/test.mp4", 1000),
                new TranscodedFileInfo("360p", videoId + "/360p/test.mp4", 2000),
                new TranscodedFileInfo("720p", videoId + "/720p/test.mp4", 3000)
        ));

        completionService.handleCompletion(completion);

        // Verify 3 transcoded files saved
        verify(transcodedFileRepository, times(3)).save(any(TranscodedFileEntity.class));

        // Verify video status updated to COMPLETED
        ArgumentCaptor<VideoEntity> captor = ArgumentCaptor.forClass(VideoEntity.class);
        verify(videoRepository).save(captor.capture());
        assertEquals(VideoStatus.COMPLETED, captor.getValue().getStatus());

        // Verify cache invalidated
        verify(redisTemplate).delete("video:metadata:" + videoId);
    }

    @Test
    void handleCompletion_videoNotFound_throws() {
        UUID videoId = UUID.randomUUID();
        when(videoRepository.findById(videoId)).thenReturn(Optional.empty());

        TranscodingCompletion completion = new TranscodingCompletion(videoId, "test.mp4", List.of());

        assertThrows(IllegalStateException.class, () -> completionService.handleCompletion(completion));
    }
}
