package com.finguard.copilot.dispatch;

import java.util.Map;
import java.time.Duration;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.MessageConverter;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(
        prefix = "finguard.copilot.dispatch",
        name = "enabled",
        havingValue = "true",
        matchIfMissing = true)
@EnableScheduling
public class RabbitTopologyConfiguration {

    public static final String ANALYSIS_EXCHANGE = "finguard.copilot.analysis.v1";
    public static final String ANALYSIS_QUEUE = "finguard.copilot.analysis.request.v1";
    public static final String ANALYSIS_ROUTING_KEY = "analysis.requested";
    public static final String RETRY_EXCHANGE = "finguard.copilot.analysis.retry.v1";
    public static final String RETRY_QUEUE = "finguard.copilot.analysis.retry.30s.v1";
    public static final String DLX_EXCHANGE = "finguard.copilot.analysis.dlx.v1";
    public static final String DLQ_QUEUE = "finguard.copilot.analysis.dlq.v1";
    public static final Duration RETRY_QUEUE_TTL = Duration.ofSeconds(30);

    @Bean
    DirectExchange analysisExchange() {
        return new DirectExchange(ANALYSIS_EXCHANGE, true, false);
    }

    @Bean
    Queue analysisQueue() {
        return new Queue(ANALYSIS_QUEUE, true);
    }

    @Bean
    Binding analysisBinding(Queue analysisQueue, DirectExchange analysisExchange) {
        return BindingBuilder.bind(analysisQueue).to(analysisExchange).with(ANALYSIS_ROUTING_KEY);
    }

    @Bean
    DirectExchange retryExchange() {
        return new DirectExchange(RETRY_EXCHANGE, true, false);
    }

    @Bean
    Queue retryQueue() {
        return new Queue(RETRY_QUEUE, true, false, false, Map.of(
                "x-message-ttl", RETRY_QUEUE_TTL.toMillis(),
                "x-dead-letter-exchange", ANALYSIS_EXCHANGE,
                "x-dead-letter-routing-key", ANALYSIS_ROUTING_KEY));
    }

    @Bean
    Binding retryBinding(Queue retryQueue, DirectExchange retryExchange) {
        return BindingBuilder.bind(retryQueue).to(retryExchange).with(ANALYSIS_ROUTING_KEY);
    }

    @Bean
    DirectExchange deadLetterExchange() {
        return new DirectExchange(DLX_EXCHANGE, true, false);
    }

    @Bean
    Queue deadLetterQueue() {
        return new Queue(DLQ_QUEUE, true);
    }

    @Bean
    Binding deadLetterBinding(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
        return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(ANALYSIS_ROUTING_KEY);
    }

    @Bean
    MessageConverter rabbitMessageConverter() {
        var converter=new Jackson2JsonMessageConverter();
        var types=new org.springframework.amqp.support.converter.DefaultJackson2JavaTypeMapper();
        // Retain the existing wire id while authorizing only this application DTO, not its package.
        types.setIdClassMapping(Map.of(AnalysisRequestedMessage.class.getName(),AnalysisRequestedMessage.class));
        converter.setJavaTypeMapper(types);
        return converter;
    }

    @Bean
    RabbitTemplate rabbitTemplate(ConnectionFactory connectionFactory, MessageConverter converter) {
        if (connectionFactory instanceof CachingConnectionFactory caching) {
            caching.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
            caching.setPublisherReturns(true);
        }
        RabbitTemplate template = new RabbitTemplate(connectionFactory);
        template.setMessageConverter(converter);
        template.setMandatory(true);
        return template;
    }

    @Bean
    RabbitAdmin rabbitAdmin(ConnectionFactory connectionFactory) {
        return new RabbitAdmin(connectionFactory);
    }
}
