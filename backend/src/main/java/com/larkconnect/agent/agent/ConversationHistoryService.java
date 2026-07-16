package com.larkconnect.agent.agent;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

@Service
public class ConversationHistoryService implements ConversationHistoryProvider {
    private final JdbcTemplate jdbcTemplate;

    public ConversationHistoryService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public List<HistoryTurn> load(String chatType, String openId, String chatId, String currentRequestId) {
        String scopeSql = "t.feishu_open_id = ? and t.feishu_chat_id = ?";
        List<Object> args = new ArrayList<>();
        args.add(openId);
        args.add(chatId);
        args.add(currentRequestId);
        String sql = """
                select t.message_text, a.final_answer
                from agent_task t join agent_audit_log a on a.request_id = t.request_id
                where %s and t.request_id <> ? and t.status = 'SUCCEEDED'
                  and a.final_answer is not null
                  and a.intent in ('direct_deepseek', 'mcp_deepseek')
                order by t.created_at desc limit 5
                """.formatted(scopeSql);
        List<HistoryTurn> newestFirst = jdbcTemplate.query(sql,
                (rs, rowNum) -> new HistoryTurn(rs.getString(1), rs.getString(2)),
                args.toArray());
        Collections.reverse(newestFirst);
        return List.copyOf(newestFirst);
    }

    public record HistoryTurn(String question, String answer) {}
}
