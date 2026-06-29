package com.larkconnect.agent.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class RabbitConfig {
    public static final String EXCHANGE = "agent.events";
    public static final String TASK_QUEUE = "agent.task.query";
    public static final String TASK_ROUTING_KEY = "agent.task.query";
    public static final String DLQ = "agent.task.query.dlq";

    @Bean
    DirectExchange agentExchange() {
        return new DirectExchange(EXCHANGE, true, false);
    }

    @Bean
    Queue agentTaskQueue() {
        return QueueBuilderSupport.durableQueue(TASK_QUEUE, DLQ);
    }

    @Bean
    Queue agentTaskDlq() {
        return new Queue(DLQ, true);
    }

    @Bean
    Binding agentTaskBinding(DirectExchange agentExchange, Queue agentTaskQueue) {
        return BindingBuilder.bind(agentTaskQueue).to(agentExchange).with(TASK_ROUTING_KEY);
    }

    @Bean
    Jackson2JsonMessageConverter jackson2JsonMessageConverter() {
        return new Jackson2JsonMessageConverter();
    }

    @Bean
    SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
            ConnectionFactory connectionFactory,
            Jackson2JsonMessageConverter converter
    ) {
        SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
        factory.setConnectionFactory(connectionFactory);
        factory.setMessageConverter(converter);
        factory.setConcurrentConsumers(2);
        factory.setMaxConcurrentConsumers(8);
        factory.setDefaultRequeueRejected(false);
        return factory;
    }
}
