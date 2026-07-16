package com.larkconnect.agent.agent;

import com.larkconnect.agent.config.RabbitConfig;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

@Component
public class QueryResumePublisher {
    private final RabbitTemplate rabbit;
    public QueryResumePublisher(RabbitTemplate rabbit) { this.rabbit = rabbit; }

    public void schedule(String requestId, Duration delay) {
        long seconds = Math.max(0, delay == null ? 0 : delay.toSeconds());
        if (seconds == 0) {
            rabbit.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.TASK_ROUTING_KEY, new AgentTaskMessage(requestId));
            return;
        }
        int bucket = RabbitConfig.QUERY_DELAYS_SECONDS.stream().filter(value -> value >= seconds)
                .findFirst().orElse(60);
        rabbit.convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.delayRoutingKey(bucket), new AgentTaskMessage(requestId));
    }
}
