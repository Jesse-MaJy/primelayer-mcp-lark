package com.larkconnect.agent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.mcp.McpAdapter.PageData;

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
        public long totalLatencyMs;
        public List<String> toolsUsed = new ArrayList<>();
        public List<String> projectsQueried = new ArrayList<>();

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("totalMcpCalls", totalMcpCalls);
            map.put("totalPages", totalPages);
            map.put("totalLatencyMs", totalLatencyMs);
            map.put("toolsUsed", toolsUsed);
            map.put("projectsQueried", projectsQueried);
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

    public void addPlanNode(String input, String output, long latencyMs) {
        addNode(new Node("plan", "model_call", "DeepSeek 工具规划", "SUCCEEDED", latencyMs,
                Map.of("modelName", "deepseek-chat", "input", truncate(input, 2000), "output", truncate(output, 2000))));
    }

    public void addMcpCallNode(String toolName, String projectId, String projectName, PageData page, int pageIdx) {
        String nodeId = "tool_" + sanitizeId(toolName) + "_" + sanitizeId(projectId) + "_p" + pageIdx;
        Map<String, Object> meta = new LinkedHashMap<>();
        meta.put("toolName", toolName);
        meta.put("projectId", projectId);
        meta.put("projectName", projectName != null ? projectName : projectId);
        meta.put("page", page.page());
        meta.put("pageSize", page.pageSize());
        meta.put("request", toJsonSafe(page.rawRequest()));
        meta.put("response", toJsonSafe(page.rawResponse()));
        addNode(new Node(nodeId, "mcp_call",
                toolName + " (" + (projectName != null ? projectName : projectId) + ", 第" + (page.page() + 1) + "页)",
                page.status(), page.latencyMs(), meta));
    }

    public void addAnalyzeNode(String nodeId, String label, String input, String output, long latencyMs) {
        addNode(new Node(nodeId, "model_call", label, "SUCCEEDED", latencyMs,
                Map.of("modelName", "deepseek-chat", "input", truncate(input, 2000), "output", truncate(output, 3000))));
    }

    public String lastNodeId() {
        return nodes.isEmpty() ? null : nodes.get(nodes.size() - 1).id();
    }

    private static String sanitizeId(String s) {
        return (s != null ? s : "unknown").replaceAll("[^a-zA-Z0-9_]", "_");
    }

    private static String truncate(String s, int max) {
        return s != null && s.length() > max ? s.substring(0, max) + "...(truncated)" : s;
    }

    private static String toJsonSafe(Object obj) {
        try {
            return new ObjectMapper().writeValueAsString(obj);
        } catch (Exception e) {
            return String.valueOf(obj);
        }
    }
}
