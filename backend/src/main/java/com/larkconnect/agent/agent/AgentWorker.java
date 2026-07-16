package com.larkconnect.agent.agent;

import com.larkconnect.agent.config.RabbitConfig;
import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.stereotype.Component;
import org.springframework.beans.factory.annotation.Autowired;

import java.time.Duration;
import java.time.Instant;

@Component
public class AgentWorker {
    private final AgentTaskService taskService;
    private final AgentOrchestrator orchestrator;
    private final QueryCheckpointRepository checkpoints;
    private final QueryResumePublisher resumePublisher;

    public AgentWorker(AgentTaskService taskService, AgentOrchestrator orchestrator) {
        this(taskService, orchestrator, null, null);
    }

    @Autowired
    public AgentWorker(AgentTaskService taskService, AgentOrchestrator orchestrator,
                       QueryCheckpointRepository checkpoints, QueryResumePublisher resumePublisher) {
        this.taskService = taskService;
        this.orchestrator = orchestrator;
        this.checkpoints = checkpoints;
        this.resumePublisher = resumePublisher;
    }

    @RabbitListener(queues = RabbitConfig.TASK_QUEUE)
    public void handle(AgentTaskMessage message) {
        if (taskService.isCancelled(message.requestId())) return;
        if (checkpoints != null) {
            checkpoints.initialize(message.requestId(), taskService.createdAt(message.requestId()));
            if (!checkpoints.claim(message.requestId(), Instant.now(), Duration.ofMinutes(1))) return;
        }
        if (!taskService.markRunning(message.requestId())) return;
        try {
            AgentOrchestrator.ProcessResult result = orchestrator.process(message.requestId());
            if (result.cancelled()) {
                finishCheckpoint(message.requestId(), QueryPhase.CANCELLED, "cancelled");
            } else if (result.pending()) {
                if (resumePublisher != null) resumePublisher.schedule(message.requestId(), result.resumeAfter());
            } else if (result.partial()) {
                taskService.markPartial(message.requestId(), result.error());
                finishCheckpoint(message.requestId(), QueryPhase.PARTIAL, result.error());
            } else if (result.succeeded()) {
                taskService.markSucceeded(message.requestId());
                finishCheckpoint(message.requestId(), QueryPhase.COMPLETED, "completed");
            } else {
                taskService.markFailed(message.requestId(), result.error());
                finishCheckpoint(message.requestId(), QueryPhase.FAILED, result.error());
            }
        } catch (QueryCancelledException cancelled) {
            finishCheckpoint(message.requestId(), QueryPhase.CANCELLED, "cancelled");
        } catch (Exception e) {
            taskService.markFailed(message.requestId(), e.getMessage());
            finishCheckpoint(message.requestId(), QueryPhase.FAILED, e.getMessage());
            throw e;
        }
    }

    private void finishCheckpoint(String requestId, QueryPhase phase, String reason) {
        if (checkpoints == null) return;
        checkpoints.load(requestId).ifPresent(session -> checkpoints.advance(requestId, session.version(), phase,
                "{\"stopReason\":\"" + String.valueOf(reason).replace("\"", "'") + "\"}", null));
    }
}
