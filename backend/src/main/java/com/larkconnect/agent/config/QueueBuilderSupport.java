package com.larkconnect.agent.config;

import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;

final class QueueBuilderSupport {
    private QueueBuilderSupport() {}

    static Queue durableQueue(String name, String dlq) {
        return QueueBuilder.durable(name)
                .deadLetterExchange("")
                .deadLetterRoutingKey(dlq)
                .build();
    }
}
