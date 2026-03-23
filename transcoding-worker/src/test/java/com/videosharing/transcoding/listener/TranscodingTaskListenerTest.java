package com.videosharing.transcoding.listener;

import com.videosharing.common.dto.TranscodingTask;
import com.videosharing.transcoding.service.TranscodingService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TranscodingTaskListenerTest {

    @Mock private TranscodingService transcodingService;
    @InjectMocks private TranscodingTaskListener listener;

    @Test
    void onTranscodingTask_delegatesToService() throws Exception {
        UUID videoId = UUID.randomUUID();
        TranscodingTask task = new TranscodingTask(videoId, "test.mp4", "originals/" + videoId + "/test.mp4");

        listener.onTranscodingTask(task);

        verify(transcodingService).processTranscodingTask(task);
    }

    @Test
    void onTranscodingTask_serviceThrows_propagates() throws Exception {
        UUID videoId = UUID.randomUUID();
        TranscodingTask task = new TranscodingTask(videoId, "test.mp4", "originals/" + videoId + "/test.mp4");

        doThrow(new RuntimeException("FFmpeg failed")).when(transcodingService).processTranscodingTask(task);

        assertThrows(RuntimeException.class, () -> listener.onTranscodingTask(task));
    }
}
