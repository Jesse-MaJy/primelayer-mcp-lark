package com.larkconnect.agent.agent;

import com.larkconnect.agent.config.AppProperties;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

@Component
public class AgentServiceClient {
    private final AppProperties properties;
    private final RestClient restClient;

    public AgentServiceClient(AppProperties properties, RestClient.Builder builder) {
        this.properties = properties;
        String baseUrl = properties.agentService().baseUrl();
        this.restClient = builder.baseUrl(baseUrl == null || baseUrl.isBlank() ? "http://localhost:8090" : baseUrl).build();
    }

    public boolean isEnabled() {
        return properties.agentService().enabled();
    }

    public AgentServiceDtos.AgentAnswerResponse answer(AgentServiceDtos.AgentAnswerRequest request) {
        if (!isEnabled()) {
            throw new IllegalStateException("Agent Service 未启用");
        }
        return restClient.post()
                .uri("/v1/agent/answer")
                .body(request)
                .retrieve()
                .body(AgentServiceDtos.AgentAnswerResponse.class);
    }
}
