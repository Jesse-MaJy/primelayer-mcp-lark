package com.larkconnect.agent.config;

import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Declarables;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.ArrayList;
import java.util.List;

@Configuration
public class RabbitConfig {
    public static final String EXCHANGE = "agent.events";
    public static final String TASK_QUEUE = "agent.task.query";
    public static final String TASK_ROUTING_KEY = "agent.task.query";
    public static final String DLQ = "agent.task.query.dlq";
    public static final List<Integer> QUERY_DELAYS_SECONDS = List.of(1, 2, 4, 5, 10, 20, 30, 60);

    public static String delayRoutingKey(int seconds) { return "agent.task.query.delay." + seconds + "s"; }

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
    Declarables delayedQueryQueues(DirectExchange agentExchange) {
        List<org.springframework.amqp.core.Declarable> declarations = new ArrayList<>();
        for (int seconds : QUERY_DELAYS_SECONDS) {
            String name = delayRoutingKey(seconds);
            Queue queue = QueueBuilder.durable(name).ttl(seconds * 1_000)
                    .deadLetterExchange(EXCHANGE).deadLetterRoutingKey(TASK_ROUTING_KEY).build();
            declarations.add(queue);
            declarations.add(BindingBuilder.bind(queue).to(agentExchange).with(name));
        }
        return new Declarables(declarations);
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
