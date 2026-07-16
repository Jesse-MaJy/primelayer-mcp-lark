package com.larkconnect.agent.agent;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ProjectClarificationRepositoryTest {
    private JdbcTemplate jdbc;
    private ProjectClarificationRepository repository;

    @BeforeEach
    void setUp() {
        jdbc = new JdbcTemplate(new DriverManagerDataSource(
                "jdbc:h2:mem:project_clarification;MODE=MySQL;DB_CLOSE_DELAY=-1", "sa", ""));
        jdbc.execute("drop table if exists agent_project_scope_clarification");
        jdbc.execute("drop table if exists agent_task");
        jdbc.execute("""
                create table agent_task(
                  id bigint auto_increment primary key, request_id varchar(128) unique,
                  feishu_open_id varchar(128), feishu_chat_id varchar(128),
                  resolved_question text, resolved_project_ids json, project_scope_notice varchar(512))
                """);
        jdbc.execute("""
                create table agent_project_scope_clarification(
                  id bigint auto_increment primary key, scope_key varchar(384) unique,
                  feishu_open_id varchar(128), feishu_chat_id varchar(128), original_request_id varchar(128) unique,
                  original_task_id bigint, original_question text, candidate_projects json,
                  status varchar(32), claimed_by_request_id varchar(128))
                """);
        repository = new ProjectClarificationRepository(jdbc, new ObjectMapper());
    }

    @Test
    void nextMessageClaimsOnceAndCanResumeSavedResolution() {
        insertTask("original");
        List<ProjectScopeService.ProjectOption> candidates = List.of(
                new ProjectScopeService.ProjectOption("P1", "一期", ""),
                new ProjectScopeService.ProjectOption("P2", "二期", ""));
        repository.save("original", "u1", "c1", "查询作业票", candidates);
        insertTask("reply");

        var claimed = repository.claimNext("reply", "u1", "c1");

        assertThat(claimed).isPresent();
        assertThat(claimed.orElseThrow().candidates()).extracting(ProjectScopeService.ProjectOption::projectId)
                .containsExactly("P1", "P2");
        assertThat(repository.claimNext("reply", "u1", "c1")).isEmpty();
        repository.complete(claimed.orElseThrow().id(), true);
        repository.saveResolution("reply", "查询作业票", List.of("P2"), null);
        assertThat(repository.loadResolution("reply")).get().satisfies(resolution -> {
            assertThat(resolution.question()).isEqualTo("查询作业票");
            assertThat(resolution.projectIds()).containsExactly("P2");
        });
    }

    private void insertTask(String requestId) {
        jdbc.update("insert into agent_task(request_id,feishu_open_id,feishu_chat_id) values (?,'u1','c1')", requestId);
    }
}
