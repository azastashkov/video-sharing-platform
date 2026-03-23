package com.videosharing.completion.config;

import org.springframework.amqp.core.*;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitMQConfig {

    public static final String TRANSCODING_COMPLETIONS_QUEUE = "transcoding-completions";
    public static final String TRANSCODING_COMPLETIONS_DLQ = "transcoding-completions-dlq";
    public static final String DLX_EXCHANGE = "dlx-exchange";

    @Bean
    public DirectExchange dlxExchange() {
        return new DirectExchange(DLX_EXCHANGE);
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
    public Binding dlqCompletionsBinding() {
        return BindingBuilder.bind(transcodingCompletionsDlq()).to(dlxExchange()).with(TRANSCODING_COMPLETIONS_DLQ);
    }

    @Bean
    public MessageConverter jsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }
}
