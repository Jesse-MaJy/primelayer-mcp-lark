package com.larkconnect.agent.ai;

import com.larkconnect.agent.admin.AdminContext;
import com.larkconnect.agent.admin.AdminPrincipal;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class PromptTemplateService {
    private static final Pattern VARIABLE = Pattern.compile("\\{\\{([a-zA-Z][a-zA-Z0-9]*)}}" );
    public static final Set<String> ALLOWED_VARIABLES = Set.of(
            "question", "temporalContext", "projectContext", "formName", "formId",
            "chunkIndex", "chunkCount", "chunkData", "chunkAnalyses", "failures");
    private static final Map<PromptStage, Set<String>> REQUIRED = Map.of(
            PromptStage.PLANNING, Set.of("question"),
            PromptStage.FORM_ANALYSIS, Set.of("chunkData"),
            PromptStage.FINAL_SUMMARY, Set.of("chunkAnalyses"),
            PromptStage.PRESENTATION, Set.of("chunkAnalyses"));

    private final JdbcTemplate jdbc;

    public PromptTemplateService(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    public String render(PromptStage stage, PromptDomain domain, Map<String, ?> variables) {
        return renderContent(stage, publishedContent(stage, domain), variables);
    }

    public String renderContent(PromptStage stage, String template, Map<String, ?> variables) {
        validate(stage, template);
        String rendered = template;
        for (String variable : ALLOWED_VARIABLES) {
            Object value = variables == null ? null : variables.get(variable);
            rendered = rendered.replace("{{" + variable + "}}", value == null ? "" : String.valueOf(value));
        }
        return rendered;
    }

    public String publishedContent(PromptStage stage, PromptDomain domain) {
        String content = publication(stage, domain);
        if (content == null && domain != PromptDomain.GLOBAL) content = publication(stage, PromptDomain.GLOBAL);
        return content == null ? defaultTemplate(stage) : content;
    }

    public List<Map<String, Object>> list() {
        List<Map<String, Object>> rows = jdbc.queryForList("""
                select v.id, v.stage, v.domain, v.version_no, v.content, v.status, v.checksum,
                       v.created_by, v.created_at, v.published_at,
                       case when p.version_id=v.id then true else false end as active
                from prompt_template_version v
                left join prompt_template_publication p on p.stage=v.stage and p.domain=v.domain
                order by v.stage, v.domain, v.version_no desc
                """);
        return rows.stream().map(this::camelize).toList();
    }

    @Transactional
    public Map<String, Object> createVersion(PromptStage stage, PromptDomain domain, String content) {
        validate(stage, content);
        String user = currentUser();
        Integer next = jdbc.queryForObject("""
                select coalesce(max(version_no), 0) + 1 from prompt_template_version where stage=? and domain=?
                """, Integer.class, stage.name(), domain.name());
        try {
            jdbc.update("""
                    insert into prompt_template_version(stage, domain, version_no, content, status, checksum, created_by)
                    values (?, ?, ?, ?, 'DRAFT', ?, ?)
                    """, stage.name(), domain.name(), next, content, checksum(content), user);
        } catch (DuplicateKeyException concurrent) {
            throw new IllegalStateException("同一提示词范围正在创建新版本，请刷新后重试", concurrent);
        }
        Long id = jdbc.queryForObject("""
                select id from prompt_template_version where stage=? and domain=? and version_no=?
                """, Long.class, stage.name(), domain.name(), next);
        return detail(id);
    }

    @Transactional
    public Map<String, Object> publish(long id, String note, boolean rollback) {
        Map<String, Object> version = rawDetail(id);
        PromptStage stage = PromptStage.valueOf(String.valueOf(version.get("stage")));
        PromptDomain domain = PromptDomain.valueOf(String.valueOf(version.get("domain")));
        validate(stage, String.valueOf(version.get("content")));
        String user = currentUser();
        jdbc.update("update prompt_template_version set status='ARCHIVED' where stage=? and domain=? and id<>?",
                stage.name(), domain.name(), id);
        jdbc.update("update prompt_template_version set status='PUBLISHED', published_at=? where id=?",
                Timestamp.from(Instant.now()), id);
        jdbc.update("""
                insert into prompt_template_publication(stage, domain, version_id, published_by, published_at)
                values (?, ?, ?, ?, ?)
                on duplicate key update version_id=values(version_id), published_by=values(published_by),
                  published_at=values(published_at)
                """, stage.name(), domain.name(), id, user, Timestamp.from(Instant.now()));
        jdbc.update("""
                insert into prompt_template_publication_audit(stage, domain, version_id, action, note, operated_by)
                values (?, ?, ?, ?, ?, ?)
                """, stage.name(), domain.name(), id, rollback ? "ROLLBACK" : "PUBLISH", safeNote(note), user);
        return detail(id);
    }

    public Map<String, Object> detail(long id) {
        return rawDetail(id);
    }

    private Map<String, Object> rawDetail(long id) {
        List<Map<String, Object>> rows = jdbc.queryForList("select * from prompt_template_version where id=?", id);
        if (rows.isEmpty()) throw new IllegalArgumentException("提示词版本不存在：" + id);
        return camelize(rows.get(0));
    }

    private String publication(PromptStage stage, PromptDomain domain) {
        List<String> values = jdbc.queryForList("""
                select v.content from prompt_template_publication p
                join prompt_template_version v on v.id=p.version_id
                where p.stage=? and p.domain=?
                """, String.class, stage.name(), domain.name());
        return values.isEmpty() ? null : values.get(0);
    }

    private void validate(PromptStage stage, String content) {
        if (content == null || content.isBlank()) throw new IllegalArgumentException("提示词内容不能为空");
        if (content.length() > 50_000) throw new IllegalArgumentException("提示词不能超过 50000 字符");
        Matcher matcher = VARIABLE.matcher(content);
        List<String> variables = new ArrayList<>();
        while (matcher.find()) {
            String variable = matcher.group(1);
            if (!ALLOWED_VARIABLES.contains(variable)) throw new IllegalArgumentException("不支持的模板变量：" + variable);
            variables.add(variable);
        }
        for (String required : REQUIRED.get(stage)) {
            if (!variables.contains(required)) throw new IllegalArgumentException("缺少必需模板变量：" + required);
        }
    }

    private String defaultTemplate(PromptStage stage) {
        return switch (stage) {
            case PLANNING -> "围绕用户问题选择必要的工程项目数据，严格使用给定时间和项目范围。问题：{{question}}\n时间：{{temporalContext}}\n项目：{{projectContext}}";
            case FORM_ANALYSIS -> "分析给定工程业务数据分块，不编造事实。表单：{{formName}}，分块 {{chunkIndex}}/{{chunkCount}}。输出 JSON：statistics、evidence、risks、dataGaps。数据：{{chunkData}}";
            case FINAL_SUMMARY -> "基于全部分块分析形成管理结论，不得要求重新查询。原问题：{{question}}\n时间：{{temporalContext}}\n分块分析：{{chunkAnalyses}}\n失败：{{failures}}";
            case PRESENTATION -> "将回答转换为清晰的工程管理展示结构，不增删事实。原始内容：{{chunkAnalyses}}";
        };
    }

    private String checksum(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception e) { throw new IllegalStateException("无法计算提示词校验值", e); }
    }

    private String currentUser() {
        AdminPrincipal principal = AdminContext.current();
        return principal == null ? "system" : principal.username();
    }

    private String safeNote(String note) {
        if (note == null) return null;
        return note.length() <= 512 ? note : note.substring(0, 512);
    }

    private Map<String, Object> camelize(Map<String, Object> source) {
        Map<String, Object> result = new LinkedHashMap<>();
        source.forEach((key, value) -> {
            String normalized = key.toLowerCase(java.util.Locale.ROOT);
            result.put(switch (normalized) {
                case "version_no" -> "versionNo";
                case "created_by" -> "createdBy";
                case "created_at" -> "createdAt";
                case "published_at" -> "publishedAt";
                default -> normalized;
            }, value);
        });
        return result;
    }
}
