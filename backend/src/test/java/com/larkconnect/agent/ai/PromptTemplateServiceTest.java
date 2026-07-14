package com.larkconnect.agent.ai;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class PromptTemplateServiceTest {
    private PromptTemplateService service;

    @BeforeEach
    void setUp() {
        JdbcTemplate jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:prompt_templates;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("drop table if exists prompt_template_publication_audit");
        jdbc.execute("drop table if exists prompt_template_publication");
        jdbc.execute("drop table if exists prompt_template_version");
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
        service = new PromptTemplateService(jdbc);
    }

    @Test
    void publishesDomainVersionAndFallsBackToGlobal() {
        Map<String, Object> global = service.createVersion(PromptStage.FORM_ANALYSIS, PromptDomain.GLOBAL,
                "全局分析：{{chunkData}}");
        service.publish(((Number) global.get("id")).longValue(), "global", false);
        assertThat(service.render(PromptStage.FORM_ANALYSIS, PromptDomain.SAFETY,
                Map.of("chunkData", "A"))).isEqualTo("全局分析：A");

        Map<String, Object> safety = service.createVersion(PromptStage.FORM_ANALYSIS, PromptDomain.SAFETY,
                "安全分析：{{chunkData}}");
        service.publish(((Number) safety.get("id")).longValue(), "safety", false);
        assertThat(service.render(PromptStage.FORM_ANALYSIS, PromptDomain.SAFETY,
                Map.of("chunkData", "B"))).isEqualTo("安全分析：B");
    }

    @Test
    void rejectsUnknownAndMissingVariables() {
        assertThatThrownBy(() -> service.createVersion(PromptStage.FORM_ANALYSIS, PromptDomain.GLOBAL,
                "无数据变量"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("chunkData");
        assertThatThrownBy(() -> service.createVersion(PromptStage.PLANNING, PromptDomain.GLOBAL,
                "{{question}} {{unsafeVariable}}"))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("unsafeVariable");
    }

    @Test
    void rollbackMovesPublicationPointerToHistoricalVersion() {
        Map<String, Object> first = service.createVersion(PromptStage.PLANNING, PromptDomain.GLOBAL,
                "第一版 {{question}}");
        Map<String, Object> second = service.createVersion(PromptStage.PLANNING, PromptDomain.GLOBAL,
                "第二版 {{question}}");
        long firstId = ((Number) first.get("id")).longValue();
        long secondId = ((Number) second.get("id")).longValue();
        service.publish(firstId, null, false);
        service.publish(secondId, null, false);
        service.publish(firstId, "rollback", true);

        assertThat(service.render(PromptStage.PLANNING, PromptDomain.GLOBAL, Map.of("question", "Q")))
                .isEqualTo("第一版 Q");
        assertThat(service.list()).anyMatch(row -> row.get("id").equals(firstId)
                && Boolean.TRUE.equals(row.get("active")));
    }
}
