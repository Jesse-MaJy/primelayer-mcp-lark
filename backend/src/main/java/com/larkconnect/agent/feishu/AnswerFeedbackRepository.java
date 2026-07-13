package com.larkconnect.agent.feishu;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.List;
import java.util.Optional;

@Repository
public class AnswerFeedbackRepository {
    private final JdbcTemplate jdbcTemplate;

    public AnswerFeedbackRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public Optional<AnswerContext> findAnswer(String requestId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    select t.request_id, t.feishu_message_id, t.message_text, a.final_answer,
                      a.presentation_json, a.intent
                    from agent_task t
                    join agent_audit_log a on a.request_id = t.request_id
                    where t.request_id = ?
                      and a.intent in ('direct_deepseek', 'mcp_deepseek')
                      and a.final_answer is not null
                    """, (rs, rowNum) -> {
                String intent = rs.getString("intent");
                String title = "mcp_deepseek".equals(intent) ? "项目数据分析" : "DeepSeek 回答";
                return new AnswerContext(
                        rs.getString("request_id"),
                        rs.getString("feishu_message_id"),
                        rs.getString("message_text"),
                        rs.getString("final_answer"),
                        rs.getString("presentation_json"),
                        title,
                        "blue"
                );
            }, requestId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    public Optional<FeedbackRecord> findFeedback(String requestId, String openId) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject("""
                    select request_id, feishu_open_id, rating, reason_code, detail, feishu_message_id,
                      last_event_id, last_event_time, updated_at
                    from agent_answer_feedback
                    where request_id = ? and feishu_open_id = ?
                    """, this::mapFeedback, requestId, openId));
        } catch (EmptyResultDataAccessException e) {
            return Optional.empty();
        }
    }

    @Transactional
    public SaveOutcome upsert(FeedbackWrite write) {
        Optional<FeedbackRecord> previous = findFeedback(write.requestId(), write.openId());
        if (previous.isPresent() && isNotNewer(write, previous.get())) {
            return new SaveOutcome(previous.get(), false);
        }

        int updated = updateExisting(write);
        if (updated == 0 && previous.isPresent()) {
            return new SaveOutcome(findFeedback(write.requestId(), write.openId()).orElseThrow(), false);
        }
        if (updated == 0 && previous.isEmpty()) {
            try {
                jdbcTemplate.update("""
                        insert into agent_answer_feedback(
                          request_id, feishu_open_id, rating, reason_code, detail, feishu_message_id,
                          last_event_id, last_event_time
                        ) values (?, ?, ?, ?, ?, ?, ?, ?)
                        """, write.requestId(), write.openId(), write.rating(), write.reasonCode(), write.detail(),
                        write.messageId(), write.eventId(), write.eventTime());
            } catch (DuplicateKeyException race) {
                updated = updateExisting(write);
                if (updated == 0) {
                    return new SaveOutcome(findFeedback(write.requestId(), write.openId()).orElseThrow(), false);
                }
            }
        }
        FeedbackRecord current = findFeedback(write.requestId(), write.openId()).orElseThrow();
        return new SaveOutcome(current, true);
    }

    public List<FeedbackDetail> listDetails(String requestId) {
        return jdbcTemplate.query("""
                select f.feishu_open_id, ub.person_name, f.rating, f.reason_code, f.detail, f.updated_at
                from agent_answer_feedback f
                left join user_binding ub on ub.feishu_open_id = f.feishu_open_id
                where f.request_id = ?
                order by f.updated_at desc, f.id desc
                """, (rs, rowNum) -> new FeedbackDetail(
                rs.getString("feishu_open_id"),
                rs.getString("person_name"),
                rs.getString("rating"),
                rs.getString("reason_code"),
                reasonLabel(rs.getString("reason_code")),
                rs.getString("detail"),
                rs.getTimestamp("updated_at")
        ), requestId);
    }

    private int updateExisting(FeedbackWrite write) {
        return jdbcTemplate.update("""
                update agent_answer_feedback
                set rating = ?, reason_code = ?, detail = ?, feishu_message_id = ?,
                  last_event_id = ?, last_event_time = ?, updated_at = current_timestamp
                where request_id = ? and feishu_open_id = ?
                  and (last_event_time < ? or (last_event_time = ? and last_event_id <> ?))
                """, write.rating(), write.reasonCode(), write.detail(), write.messageId(),
                write.eventId(), write.eventTime(), write.requestId(), write.openId(),
                write.eventTime(), write.eventTime(), write.eventId());
    }

    private boolean isNotNewer(FeedbackWrite write, FeedbackRecord previous) {
        return write.eventTime() < previous.lastEventTime()
                || (write.eventTime() == previous.lastEventTime() && write.eventId().equals(previous.lastEventId()));
    }

    private FeedbackRecord mapFeedback(ResultSet rs, int rowNum) throws SQLException {
        return new FeedbackRecord(
                rs.getString("request_id"),
                rs.getString("feishu_open_id"),
                rs.getString("rating"),
                rs.getString("reason_code"),
                rs.getString("detail"),
                rs.getString("feishu_message_id"),
                rs.getString("last_event_id"),
                rs.getLong("last_event_time"),
                rs.getTimestamp("updated_at")
        );
    }

    private String reasonLabel(String code) {
        if (code == null) return null;
        return switch (code) {
            case "DATA_INACCURATE" -> "数据不准确";
            case "OFF_TOPIC" -> "答非所问";
            case "INCOMPLETE" -> "内容不完整";
            case "OTHER" -> "其他";
            default -> code;
        };
    }

    public record AnswerContext(String requestId, String messageId, String question, String answer,
                                String presentationJson, String title, String template) {
        public AnswerContext(String requestId, String messageId, String question, String answer,
                             String title, String template) {
            this(requestId, messageId, question, answer, null, title, template);
        }
    }

    public record FeedbackWrite(String requestId, String openId, String rating, String reasonCode, String detail,
                                String messageId, String eventId, long eventTime) {}

    public record FeedbackRecord(String requestId, String openId, String rating, String reasonCode, String detail,
                                 String messageId, String lastEventId, long lastEventTime, Timestamp updatedAt) {}

    public record SaveOutcome(FeedbackRecord record, boolean applied) {}

    public record FeedbackDetail(String feishuOpenId, String personName, String rating, String reasonCode,
                                 String reasonLabel, String detail, Timestamp updatedAt) {}
}
