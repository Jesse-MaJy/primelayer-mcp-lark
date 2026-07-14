package com.larkconnect.agent.ai;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.larkconnect.agent.crypto.TokenCryptoService;
import com.larkconnect.agent.deepseek.DeepSeekConversationClient;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptReplayServiceTest {
    private JdbcTemplate jdbc;
    private PromptTemplateService templates;
    private PromptReplayService replay;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:prompt_replay;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("drop all objects");
        jdbc.execute("""
                create table system_config (
                  config_key varchar(128) primary key, config_value clob, description varchar(512), is_sensitive int)
                """);
        jdbc.execute("""
                create table prompt_template_version (
                  id bigint auto_increment primary key, stage varchar(32), domain varchar(32), version_no int,
                  content clob, status varchar(16), checksum varchar(64), created_by varchar(128),
                  created_at timestamp default current_timestamp, published_at timestamp,
                  unique(stage, domain, version_no))
                """);
        jdbc.execute("""
                create table prompt_template_publication (
                  stage varchar(32), domain varchar(32), version_id bigint, published_by varchar(128),
                  published_at timestamp, primary key(stage, domain))
                """);
        jdbc.execute("""
                create table prompt_template_publication_audit (
                  id bigint auto_increment primary key, stage varchar(32), domain varchar(32), version_id bigint,
                  action varchar(16), note varchar(512), operated_by varchar(128), operated_at timestamp default current_timestamp)
                """);
        jdbc.execute("""
                create table prompt_replay_snapshot (
                  id bigint auto_increment primary key, request_id varchar(128), stage varchar(32), domain varchar(32),
                  form_id varchar(128), form_name varchar(256), chunk_index int, chunk_count int, record_count int,
                  input_chars int, encrypted_payload clob, created_at timestamp default current_timestamp,
                  deleted_at timestamp, unique(request_id, stage, form_id, chunk_index))
                """);
        templates = new PromptTemplateService(jdbc);
        DeepSeekConversationClient fakeDeepSeek = new DeepSeekConversationClient() {
            @Override
            public Completion complete(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                                       boolean allowTools) {
                String content = String.valueOf(messages.get(messages.size() - 1).get("content"));
                return new Completion(content, List.of(), Map.of(), 3, 2);
            }

            @Override
            public String analyzeChunk(String toolName, String projectId, String json, int chunkIndex, int chunkCount) {
                return json;
            }

            @Override
            public String model() {
                return "fake";
            }
        };
        replay = new PromptReplayService(jdbc, new TokenCryptoService(jdbc), new ObjectMapper(),
                templates, fakeDeepSeek);
    }

    @Test
    void encryptsReplaysAndSoftDeletesSnapshot() {
        String marker = "罗诊项目-不可明文读取";
        replay.saveChunk("request-1", PromptDomain.SAFETY, "form-1", "安全作业票",
                1, 1, 1, marker.length(), marker);

        String encrypted = jdbc.queryForObject(
                "select encrypted_payload from prompt_replay_snapshot where request_id='request-1'", String.class);
        assertThat(encrypted).isNotBlank().doesNotContain(marker);
        assertThat(replay.listSnapshots()).hasSize(1);

        Map<String, Object> candidate = templates.createVersion(PromptStage.FORM_ANALYSIS, PromptDomain.SAFETY,
                "候选分析 {{chunkData}}");
        long versionId = ((Number) candidate.get("id")).longValue();
        long snapshotId = jdbc.queryForObject(
                "select id from prompt_replay_snapshot where request_id='request-1'", Long.class);
        Map<String, Object> result = replay.replay(versionId, snapshotId);

        assertThat(result.get("currentOutput").toString()).contains(marker);
        assertThat(result.get("candidateOutput").toString()).contains("候选分析").contains(marker);

        replay.deleteSnapshot(snapshotId);
        assertThat(replay.listSnapshots()).isEmpty();
        assertThatThrownBy(() -> replay.replay(versionId, snapshotId))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("不存在");
    }
}
