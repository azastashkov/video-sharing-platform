package com.videosharing.completion.listener;

import com.videosharing.common.dto.TranscodedFileInfo;
import com.videosharing.common.dto.TranscodingCompletion;
import com.videosharing.completion.service.CompletionService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class CompletionListenerTest {

    @Mock private CompletionService completionService;
    @InjectMocks private CompletionListener completionListener;

    @Test
    void onCompletion_delegatesToService() {
        UUID videoId = UUID.randomUUID();
        TranscodingCompletion completion = new TranscodingCompletion(videoId, "test.mp4", List.of(
                new TranscodedFileInfo("720p", videoId + "/720p/test.mp4", 5000)
        ));

        completionListener.onCompletion(completion);

        verify(completionService).handleCompletion(completion);
    }

    @Test
    void onCompletion_serviceThrows_propagates() {
        UUID videoId = UUID.randomUUID();
        TranscodingCompletion completion = new TranscodingCompletion(videoId, "test.mp4", List.of());

        doThrow(new IllegalStateException("Video not found")).when(completionService).handleCompletion(completion);

        assertThrows(RuntimeException.class, () -> completionListener.onCompletion(completion));
    }
}
