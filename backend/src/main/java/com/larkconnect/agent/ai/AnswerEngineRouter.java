package com.larkconnect.agent.ai;

import com.larkconnect.agent.agent.IntentCategory;
import com.larkconnect.agent.agent.IntentRoute;
import org.springframework.stereotype.Component;

@Component
public class AnswerEngineRouter {
    private final AiRuntimeConfigService configService;
    private final FastGptAnswerEngine fastGptAnswerEngine;

    public AnswerEngineRouter(AiRuntimeConfigService configService, FastGptAnswerEngine fastGptAnswerEngine) {
        this.configService = configService;
        this.fastGptAnswerEngine = fastGptAnswerEngine;
    }

    public boolean shouldUseFastGpt(IntentRoute route) {
        AiRuntimeConfigService.AiSettings settings = configService.loadSettings();
        return settings.engine() == AiEngineType.FASTGPT && canFastGptHandle(route);
    }

    public FastGptClient.FastGptResponse answerWithFastGpt(FastGptClient.FastGptRequest request) {
        return fastGptAnswerEngine.answer(request, configService.loadSettings());
    }

    private boolean canFastGptHandle(IntentRoute route) {
        return route.category() != IntentCategory.MCP_CONFIG_STATUS
                && route.category() != IntentCategory.SYSTEM_CONFIG;
    }
}
