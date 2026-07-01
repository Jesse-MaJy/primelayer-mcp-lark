package com.larkconnect.agent.ai;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.util.List;
import java.util.Map;

@Component
public class FastGptClient {
    private final ObjectMapper objectMapper;
    private final RestClient.Builder restClientBuilder;

    public FastGptClient(ObjectMapper objectMapper, RestClient.Builder restClientBuilder, AiRuntimeConfigService ignoredConfigService) {
        this.objectMapper = objectMapper;
        this.restClientBuilder = restClientBuilder;
    }

    public FastGptResponse answer(FastGptRequest request, AiRuntimeConfigService.AiSettings settings) {
        if (!hasText(settings.fastGptBaseUrl())) {
            throw new IllegalStateException("FastGPT BaseURL 未配置");
        }
        if (!hasText(settings.fastGptApiKey())) {
            throw new IllegalStateException("FastGPT API Key 未配置");
        }
        long started = System.currentTimeMillis();
        Map<String, Object> response = restClient(settings).post()
                .uri("/v1/chat/completions")
                .header("Authorization", "Bearer " + settings.fastGptApiKey())
                .body(body(request, settings))
                .retrieve()
                .body(Map.class);
        JsonNode root = objectMapper.valueToTree(response);
        String answer = root.path("choices").path(0).path("message").path("content").asText("");
        if (!hasText(answer)) {
            throw new IllegalStateException("FastGPT 返回为空");
        }
        return new FastGptResponse(answer, root.toString(), System.currentTimeMillis() - started);
    }

    private RestClient restClient(AiRuntimeConfigService.AiSettings settings) {
        SimpleClientHttpRequestFactory requestFactory = new SimpleClientHttpRequestFactory();
        requestFactory.setConnectTimeout(settings.fastGptTimeoutMs());
        requestFactory.setReadTimeout(settings.fastGptTimeoutMs());
        return restClientBuilder
                .requestFactory(requestFactory)
                .baseUrl(trimTrailingSlash(settings.fastGptBaseUrl()))
                .build();
    }

    private Map<String, Object> body(FastGptRequest request, AiRuntimeConfigService.AiSettings settings) {
        String context = """
                Feishu context:
                requestId=%s
                openId=%s
                chatId=%s
                chatType=%s
                """.formatted(request.requestId(), request.openId(), request.chatId(), request.chatType());
        return Map.of(
                "model", settings.fastGptModel(),
                "chatId", settings.fastGptMemoryEnabled() ? request.chatId() : request.requestId(),
                "messages", List.of(
                        Map.of("role", "system", "content", context),
                        Map.of("role", "user", "content", request.question())
                ),
                "temperature", 0.2
        );
    }

    private String trimTrailingSlash(String value) {
        String trimmed = value.trim();
        while (trimmed.endsWith("/")) {
            trimmed = trimmed.substring(0, trimmed.length() - 1);
        }
        return trimmed;
    }

    private boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    public record FastGptRequest(String requestId, String question, String openId, String chatId, String chatType) {}
    public record FastGptResponse(String answer, String rawResponse, long latencyMs) {}
}
