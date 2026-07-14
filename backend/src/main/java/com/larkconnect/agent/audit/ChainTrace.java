package com.larkconnect.agent.audit;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public class ChainTrace {
    private final String requestId;
    public final List<Node> nodes = new ArrayList<>();
    public final List<Edge> edges = new ArrayList<>();
    public final Summary summary = new Summary();

    public ChainTrace(String requestId) {
        this.requestId = requestId;
    }

    public record Node(
            String id, String type, String label,
            String status, long latencyMs,
            Map<String, Object> metadata
    ) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("id", id);
            map.put("type", type);
            map.put("label", label);
            map.put("status", status);
            map.put("latencyMs", latencyMs);
            if (metadata != null) map.putAll(metadata);
            return map;
        }
    }

    public record Edge(String from, String to) {
        Map<String, String> toMap() {
            return Map.of("from", from, "to", to);
        }
    }

    public static class Summary {
        public int totalMcpCalls;
        public int totalPages;
        public int totalChunks;
        public int toolRounds;
        public int logicalToolCalls;
        public int historyTurns;
        public int inputTokens;
        public int outputTokens;
        public int cacheHits;
        public int savedMcpCalls;
        public long totalLatencyMs;
        public String path;
        public String model;
        public String stopReason;
        public List<String> toolsUsed = new ArrayList<>();
        public List<String> projectsQueried = new ArrayList<>();
        public List<String> failures = new ArrayList<>();

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("totalMcpCalls", totalMcpCalls);
            map.put("totalPages", totalPages);
            map.put("totalChunks", totalChunks);
            map.put("toolRounds", toolRounds);
            map.put("logicalToolCalls", logicalToolCalls);
            map.put("historyTurns", historyTurns);
            map.put("inputTokens", inputTokens);
            map.put("outputTokens", outputTokens);
            map.put("cacheHits", cacheHits);
            map.put("savedMcpCalls", savedMcpCalls);
            map.put("totalLatencyMs", totalLatencyMs);
            map.put("path", path);
            map.put("model", model);
            map.put("stopReason", stopReason);
            map.put("toolsUsed", toolsUsed);
            map.put("projectsQueried", projectsQueried);
            map.put("failures", failures);
            return map;
        }
    }

    Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("requestId", requestId);
        map.put("nodes", nodes.stream().map(Node::toMap).toList());
        map.put("edges", edges.stream().map(Edge::toMap).toList());
        map.put("summary", summary.toMap());
        return map;
    }

    public void addNode(Node node) {
        nodes.add(node);
    }

    public void addEdge(Edge edge) {
        edges.add(edge);
    }

}
