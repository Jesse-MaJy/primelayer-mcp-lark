package com.larkconnect.agent.admin;

import com.larkconnect.agent.common.ApiResponse;
import com.larkconnect.agent.deepseek.DeepSeekPlan;
import jakarta.validation.Valid;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/admin/debug")
public class DebugController {
    private final DebugService debugService;

    public DebugController(DebugService debugService) {
        this.debugService = debugService;
    }

    @GetMapping("/health")
    public ApiResponse<Map<String, Object>> health() {
        return ApiResponse.ok(debugService.health());
    }

    @PostMapping("/deepseek/plan")
    public ApiResponse<DeepSeekPlan> plan(@Valid @RequestBody DebugDtos.DeepSeekPlanRequest request) {
        return ApiResponse.ok(debugService.plan(request));
    }

    @PostMapping("/deepseek/summarize")
    public ApiResponse<Map<String, Object>> summarize(@Valid @RequestBody DebugDtos.DeepSeekSummarizeRequest request) {
        return ApiResponse.ok(debugService.summarize(request));
    }

    @PostMapping("/mcp/call")
    public ApiResponse<Map<String, Object>> callMcp(@Valid @RequestBody DebugDtos.McpCallRequest request) {
        return ApiResponse.ok(debugService.callMcp(request));
    }

    @PostMapping("/feishu/mock-event")
    public ApiResponse<Map<String, Object>> mockFeishuEvent(@Valid @RequestBody DebugDtos.FeishuMockEventRequest request) {
        return ApiResponse.ok(debugService.mockFeishuEvent(request));
    }

    @PostMapping("/agent/query")
    public ApiResponse<Map<String, Object>> queryAgent(@Valid @RequestBody DebugDtos.AgentQueryRequest request) {
        return ApiResponse.ok(debugService.queryAgent(request));
    }
}
