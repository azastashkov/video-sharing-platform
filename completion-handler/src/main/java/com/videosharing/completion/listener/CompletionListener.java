package com.videosharing.completion.listener;

import com.videosharing.common.dto.TranscodingCompletion;
import com.videosharing.completion.config.RabbitMQConfig;
import com.videosharing.completion.service.CompletionService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class CompletionListener {

    private static final Logger log = LoggerFactory.getLogger(CompletionListener.class);

    private final CompletionService completionService;

    public CompletionListener(CompletionService completionService) {
        this.completionService = completionService;
    }

    @RabbitListener(queues = RabbitMQConfig.TRANSCODING_COMPLETIONS_QUEUE)
    public void onCompletion(TranscodingCompletion completion) {
        log.info("Received completion: videoId={}", completion.videoId());
        try {
            completionService.handleCompletion(completion);
        } catch (Exception e) {
            log.error("Completion handling failed: videoId={}", completion.videoId(), e);
            throw new RuntimeException("Completion handling failed for " + completion.videoId(), e);
        }
    }
}
