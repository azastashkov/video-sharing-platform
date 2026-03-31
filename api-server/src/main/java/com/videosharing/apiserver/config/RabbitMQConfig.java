package com.videosharing.apiserver.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String TRANSCODING_TASKS_QUEUE = "transcoding-tasks";
    public static final String TRANSCODING_TASKS_DLQ = "transcoding-tasks-dlq";
    public static final String TRANSCODING_COMPLETIONS_QUEUE = "transcoding-completions";
    public static final String TRANSCODING_COMPLETIONS_DLQ = "transcoding-completions-dlq";
    public static final String DLX_EXCHANGE = "dlx-exchange";

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(DLX_EXCHANGE);
    }

    @Bean
    public Queue transcodingTasksQueue() {
        return QueueBuilder.durable(TRANSCODING_TASKS_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", TRANSCODING_TASKS_DLQ)
                .build();
    }

    @Bean
    public Queue transcodingTasksDlq() {
        return QueueBuilder.durable(TRANSCODING_TASKS_DLQ).build();
    }

    @Bean
    public Queue transcodingCompletionsQueue() {
        return QueueBuilder.durable(TRANSCODING_COMPLETIONS_QUEUE)
                .withArgument("x-dead-letter-exchange", DLX_EXCHANGE)
                .withArgument("x-dead-letter-routing-key", TRANSCODING_COMPLETIONS_DLQ)
                .build();
    }

    @Bean
    public Queue transcodingCompletionsDlq() {
        return QueueBuilder.durable(TRANSCODING_COMPLETIONS_DLQ).build();
    }

    @Bean
    public Binding transcodingTasksDlqBinding() {
        return BindingBuilder.bind(transcodingTasksDlq()).to(dlxExchange()).with(TRANSCODING_TASKS_DLQ);
    }

    @Bean
    public Binding transcodingCompletionsDlqBinding() {
        return BindingBuilder.bind(transcodingCompletionsDlq()).to(dlxExchange()).with(TRANSCODING_COMPLETIONS_DLQ);
    }

    @Bean
    public Jackson2JsonMessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
