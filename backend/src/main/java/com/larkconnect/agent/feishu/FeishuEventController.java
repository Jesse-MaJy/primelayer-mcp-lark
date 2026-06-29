package com.larkconnect.agent.feishu;

import com.larkconnect.agent.agent.AgentTaskService;
import com.larkconnect.agent.common.ApiResponse;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/feishu")
public class FeishuEventController {
    private final FeishuEventParser parser;
    private final AgentTaskService taskService;

    public FeishuEventController(FeishuEventParser parser, AgentTaskService taskService) {
        this.parser = parser;
        this.taskService = taskService;
    }

    @PostMapping("/events")
    public Object receive(@RequestBody Map<String, Object> body) {
        if (body.containsKey("challenge")) {
            return Map.of("challenge", body.get("challenge"));
        }
        FeishuIncomingMessage message = parser.parse(body);
        if (message == null) {
            return ApiResponse.ok("ignored");
        }
        taskService.createAndPublish(message);
        return ApiResponse.ok("accepted");
    }
}
