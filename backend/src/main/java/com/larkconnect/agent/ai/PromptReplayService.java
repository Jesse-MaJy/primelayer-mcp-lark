package com.larkconnect.agent.ai;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.crypto.TokenCryptoService;
import com.larkconnect.agent.deepseek.DeepSeekConversationClient;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Service
public class PromptReplayService {
    private final JdbcTemplate jdbc;
    private final TokenCryptoService crypto;
    private final ObjectMapper mapper;
    private final PromptTemplateService templates;
    private final DeepSeekConversationClient deepSeek;

    public PromptReplayService(JdbcTemplate jdbc, TokenCryptoService crypto, ObjectMapper mapper,
                               PromptTemplateService templates, DeepSeekConversationClient deepSeek) {
        this.jdbc = jdbc;
        this.crypto = crypto;
        this.mapper = mapper;
        this.templates = templates;
        this.deepSeek = deepSeek;
    }

    public void saveChunk(String requestId, PromptDomain domain, String formId, String formName,
                          int chunkIndex, int chunkCount, int recordCount, int inputChars, String chunkData) {
        try {
            String payload = mapper.writeValueAsString(Map.of(
                    "formId", formId, "formName", formName,
                    "chunkIndex", chunkIndex, "chunkCount", chunkCount, "chunkData", chunkData));
            jdbc.update("""
                    insert into prompt_replay_snapshot(request_id, stage, domain, form_id, form_name,
                      chunk_index, chunk_count, record_count, input_chars, encrypted_payload)
                    values (?, 'FORM_ANALYSIS', ?, ?, ?, ?, ?, ?, ?, ?)
                    on duplicate key update record_count=values(record_count), input_chars=values(input_chars),
                      encrypted_payload=values(encrypted_payload), deleted_at=null
                    """, requestId, domain.name(), formId, formName, chunkIndex, chunkCount,
                    recordCount, inputChars, crypto.encrypt(payload));
        } catch (Exception e) {
            throw new IllegalStateException("提示词回放快照保存失败", e);
        }
    }

    public List<Map<String, Object>> listSnapshots() {
        return jdbc.queryForList("""
                select id, request_id as requestId, stage, domain, form_id as formId, form_name as formName,
                       chunk_index as chunkIndex, chunk_count as chunkCount, record_count as recordCount,
                       input_chars as inputChars, created_at as createdAt
                from prompt_replay_snapshot where deleted_at is null order by created_at desc limit 200
                """);
    }

    public void deleteSnapshot(long id) {
        int updated = jdbc.update("update prompt_replay_snapshot set deleted_at=? where id=? and deleted_at is null",
                Timestamp.from(Instant.now()), id);
        if (updated == 0) throw new IllegalArgumentException("回放快照不存在：" + id);
    }

    public Map<String, Object> replay(long versionId, long snapshotId) {
        Map<String, Object> version = templates.detail(versionId);
        PromptStage stage = PromptStage.valueOf(String.valueOf(version.get("stage")));
        if (stage != PromptStage.FORM_ANALYSIS) {
            throw new IllegalArgumentException("当前回放快照仅支持 FORM_ANALYSIS 模板");
        }
        List<Map<String, Object>> rows = jdbc.queryForList(
                "select * from prompt_replay_snapshot where id=? and deleted_at is null", snapshotId);
        if (rows.isEmpty()) throw new IllegalArgumentException("回放快照不存在：" + snapshotId);
        Map<String, Object> row = rows.get(0);
        PromptDomain domain = PromptDomain.valueOf(String.valueOf(row.get("domain")));
        Map<String, Object> variables = decrypt(String.valueOf(row.get("encrypted_payload")));
        String current = templates.render(stage, domain, variables);
        String candidate = templates.renderContent(stage, String.valueOf(version.get("content")), variables);
        DeepSeekConversationClient.Completion currentResult = deepSeek.complete(
                List.of(Map.of("role", "user", "content", current)), List.of(), false);
        DeepSeekConversationClient.Completion candidateResult = deepSeek.complete(
                List.of(Map.of("role", "user", "content", candidate)), List.of(), false);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("snapshotId", snapshotId);
        result.put("candidateVersionId", versionId);
        result.put("domain", domain.name());
        result.put("currentOutput", currentResult.content());
        result.put("candidateOutput", candidateResult.content());
        result.put("currentTokens", currentResult.inputTokens() + currentResult.outputTokens());
        result.put("candidateTokens", candidateResult.inputTokens() + candidateResult.outputTokens());
        return result;
    }

    private Map<String, Object> decrypt(String encrypted) {
        try {
            return mapper.readValue(crypto.decrypt(encrypted), new TypeReference<LinkedHashMap<String, Object>>() {});
        } catch (Exception e) {
            throw new IllegalStateException("回放快照无法解密", e);
        }
    }
}
