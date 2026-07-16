package com.larkconnect.agent.agent;

import com.larkconnect.agent.config.RabbitConfig;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import java.time.Duration;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

class QueryResumePublisherTest {
    @Test
    void routesResumeToNearestConfiguredDelayQueue() {
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        QueryResumePublisher publisher = new QueryResumePublisher(rabbit);

        publisher.schedule("r1", Duration.ofSeconds(18));

        verify(rabbit).convertAndSend(RabbitConfig.EXCHANGE, RabbitConfig.delayRoutingKey(20),
                new AgentTaskMessage("r1"));
    }
}
