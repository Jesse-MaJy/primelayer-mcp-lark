package com.larkconnect.agent.agent;

import java.util.Map;

public record QueryContinuation(String kind, String toolName, String projectId,
                                Map<String, Object> arguments, String cursor,
                                Integer page, Integer offset, int pollAttempts,
                                int retryCount, Map<String, Object> statistics) {}
