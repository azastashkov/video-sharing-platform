package com.videosharing.transcoding.listener;

import com.videosharing.common.dto.TranscodingTask;
import com.videosharing.transcoding.config.RabbitMQConfig;
import com.videosharing.transcoding.service.TranscodingService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class TranscodingTaskListener {

    private static final Logger log = LoggerFactory.getLogger(TranscodingTaskListener.class);

    private final TranscodingService transcodingService;

    public TranscodingTaskListener(TranscodingService transcodingService) {
        this.transcodingService = transcodingService;
    }

    @RabbitListener(queues = RabbitMQConfig.TRANSCODING_TASKS_QUEUE)
    public void onTranscodingTask(TranscodingTask task) {
        log.info("Received transcoding task: videoId={}, filename={}", task.videoId(), task.originalFilename());
        try {
            transcodingService.processTranscodingTask(task);
        } catch (Exception e) {
            log.error("Transcoding failed: videoId={}", task.videoId(), e);
            throw new RuntimeException("Transcoding failed for " + task.videoId(), e);
        }
    }
}
