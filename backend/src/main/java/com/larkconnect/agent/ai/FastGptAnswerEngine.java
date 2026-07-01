package com.larkconnect.agent.ai;

import org.springframework.stereotype.Component;

@Component
public class FastGptAnswerEngine {
    private final FastGptClient fastGptClient;

    public FastGptAnswerEngine(FastGptClient fastGptClient) {
        this.fastGptClient = fastGptClient;
    }

    public FastGptClient.FastGptResponse answer(FastGptClient.FastGptRequest request, AiRuntimeConfigService.AiSettings settings) {
        return fastGptClient.answer(request, settings);
    }
}
