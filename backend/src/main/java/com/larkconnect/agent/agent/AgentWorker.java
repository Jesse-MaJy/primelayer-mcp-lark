package com.larkconnect.agent.agent;

import com.larkconnect.agent.config.RabbitConfig;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;

@Component
public class AgentWorker {
    private final AgentTaskService taskService;
    private final AgentOrchestrator orchestrator;

    public AgentWorker(AgentTaskService taskService, AgentOrchestrator orchestrator) {
        this.taskService = taskService;
        this.orchestrator = orchestrator;
    }

    @RabbitListener(queues = RabbitConfig.TASK_QUEUE)
    public void handle(AgentTaskMessage message) {
        taskService.markRunning(message.requestId());
        try {
            if (orchestrator.process(message.requestId())) {
                taskService.markSucceeded(message.requestId());
            } else {
                taskService.markFailed(message.requestId(), "AI 服务暂不可用");
            }
        } catch (Exception e) {
            taskService.markFailed(message.requestId(), e.getMessage());
            throw e;
        }
    }
}
