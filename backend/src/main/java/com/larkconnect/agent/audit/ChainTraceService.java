package com.larkconnect.agent.audit;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class ChainTraceService {
    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper;

    public ChainTraceService(JdbcTemplate jdbcTemplate, ObjectMapper objectMapper) {
        this.jdbcTemplate = jdbcTemplate;
        this.objectMapper = objectMapper;
    }

    public void save(String requestId, ChainTrace trace) {
        jdbcTemplate.update(
                "insert into agent_chain_trace(request_id, trace_data) values (?, cast(? as json)) on duplicate key update trace_data = values(trace_data)",
                requestId, toJson(trace.toMap())
        );
    }

    @SuppressWarnings("unchecked")
    public Optional<Map<String, Object>> load(String requestId) {
        List<Map<String, Object>> rows = jdbcTemplate.queryForList(
                "select trace_data from agent_chain_trace where request_id = ?", requestId
        );
        if (rows.isEmpty()) return Optional.empty();
        Object data = rows.get(0).get("trace_data");
        if (data instanceof Map<?, ?> m) {
            return Optional.of((Map<String, Object>) m);
        }
        try {
            return Optional.of(objectMapper.readValue(String.valueOf(data), Map.class));
        } catch (Exception e) {
            return Optional.empty();
        }
    }

    private String toJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (Exception e) {
            return "{}";
        }
    }
}
