package com.larkconnect.agent.admin;

import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public final class FeishuDemoCardCatalog {
    private static final ZoneId CHINA = ZoneId.of("Asia/Shanghai");
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("MM-dd");

    public record CardPreset(String key, String label, String description,
                             String color, Map<String, Object> card) {}

    public List<CardPreset> presets() {
        List<String> days = lastSevenDays();
        return List.of(
                answer(days),
                basic(days),
                todo(),
                construction(days),
                risk(),
                weekly(days)
        );
    }

    private CardPreset answer(List<String> days) {
        List<Object> elements = new ArrayList<>();
        elements.add(markdown("**问题**：今天 Roche 项目的整体施工情况如何？\n\n" +
                "**结论**：项目总体可控，实际完成率 **86%**，较计划低 2 个百分点。" +
                "需要优先处理空调末端设备到场和 5 个待确认机电点位。"));
        elements.add(table(
                columns("metric", "指标", "value", "当前值", "change", "较昨日", "owner", "责任人"),
                List.of(
                        row("metric", "实际完成率", "value", "86%", "change", "+4%", "owner", "张伟"),
                        row("metric", "计划完成率", "value", "88%", "change", "+3%", "owner", "PMO"),
                        row("metric", "已完成任务", "value", "18", "change", "+6", "owner", "各专业"),
                        row("metric", "未关闭风险", "value", "3", "change", "-1", "owner", "李敏"),
                        row("metric", "现场人员", "value", "42", "change", "+5", "owner", "王强")
                )));
        elements.add(chart("Roche 七日计划与实际进度", "line", seriesValues(
                days,
                List.of("计划完成率", "实际完成率"),
                List.of(List.of(62, 67, 72, 77, 82, 85, 88), List.of(60, 65, 70, 74, 78, 82, 86))
        )));
        elements.add(chart("任务状态占比", "pie", pieValues(
                List.of("已完成", "进行中", "有风险", "待确认"), List.of(18, 7, 3, 5))));
        elements.add(button("查看项目详情", "open_project_detail", "primary", Map.of("project_id", "demo-roche")));
        return preset("primelayer-answer", "AI 回答卡片", "指标表格、七日趋势和任务状态占比", "#1455d9",
                card("Primelayer AI 回答", "blue", elements));
    }

    private CardPreset basic(List<String> days) {
        List<Object> elements = new ArrayList<>();
        elements.add(markdown("## JSON 2.0 综合组件验证\n\n- **Markdown**：标题、列表、粗体和 [链接](https://open.feishu.cn/)\n" +
                "- **原生组件**：表格、柱状图、折线图、饼图、回调按钮"));
        elements.add(table(
                columns("component", "组件", "sample", "Demo 内容", "expected", "预期结果"),
                List.of(
                        row("component", "Markdown", "sample", "标题 / 列表 / 链接", "expected", "富文本显示"),
                        row("component", "Table", "sample", "5 行 3 列", "expected", "原生表格"),
                        row("component", "Bar", "sample", "三项目完成率", "expected", "柱状图"),
                        row("component", "Line", "sample", "七日趋势", "expected", "折线图"),
                        row("component", "Pie", "sample", "状态占比", "expected", "饼图")
                )));
        elements.add(chart("三项目完成率", "bar", categoryValues(
                List.of("Roche", "Siemens", "XDL"), List.of(86, 79, 92), "实际完成率")));
        elements.add(chart("七日关闭问题趋势", "line", categoryValues(
                days, List.of(2, 3, 4, 3, 5, 6, 7), "关闭问题")));
        elements.add(chart("组件验证状态", "pie", pieValues(
                List.of("通过", "待验证", "失败"), List.of(4, 1, 0))));
        elements.add(button("回调按钮测试", "debug_card_button", "primary", Map.of("demo", true)));
        return preset("basic-test", "基础测试卡片", "一次验证 Markdown、表格、三类图表和按钮", "#1677ff",
                card("飞书 JSON 2.0 综合测试", "blue", elements));
    }

    private CardPreset todo() {
        List<Object> elements = new ArrayList<>();
        elements.add(markdown("**今日共 8 项待办**，其中高优先级 3 项。建议上午先关闭材料和点位阻塞，下午集中处理验收资料。"));
        elements.add(chart("待办优先级占比", "pie", pieValues(
                List.of("高", "中", "低"), List.of(3, 3, 2))));
        elements.add(table(
                columns("project", "项目", "task", "待办", "priority", "优先级", "owner", "负责人", "deadline", "截止", "status", "状态"),
                List.of(
                        row("project", "Roche", "task", "确认空调末端设备 ETA", "priority", "高", "owner", "李敏", "deadline", "10:30", "status", "处理中"),
                        row("project", "Roche", "task", "关闭 5 个机电点位", "priority", "高", "owner", "张伟", "deadline", "12:00", "status", "未开始"),
                        row("project", "Siemens", "task", "消防材料复检", "priority", "高", "owner", "王强", "deadline", "14:00", "status", "待供应商"),
                        row("project", "XDL", "task", "更新高压桥架方案", "priority", "中", "owner", "陈晨", "deadline", "15:00", "status", "处理中"),
                        row("project", "Roche", "task", "归档隐蔽验收资料", "priority", "中", "owner", "赵欣", "deadline", "16:00", "status", "未开始"),
                        row("project", "Siemens", "task", "协调暖通交叉作业", "priority", "中", "owner", "刘洋", "deadline", "17:00", "status", "已安排"),
                        row("project", "XDL", "task", "补充现场问题照片", "priority", "低", "owner", "孙悦", "deadline", "18:00", "status", "未开始"),
                        row("project", "Roche", "task", "同步日报到项目群", "priority", "低", "owner", "周林", "deadline", "18:30", "status", "自动执行")
                )));
        elements.add(button("查看全部待办", "open_daily_todos", "primary", Map.of("scope", "all-projects")));
        return preset("daily-todo", "每日待办", "三项目待办、优先级占比和负责人明细", "#22a06b",
                card("今日待办提醒", "green", elements));
    }

    private CardPreset construction(List<String> days) {
        List<Object> elements = new ArrayList<>();
        elements.add(markdown("**Roche 施工日报**\n\n今日完成 2F 弱电桥架安装 120m、3F 隔墙龙骨复核和 B1 堆场重新分区。" +
                "实际进度为 **86%**，安全事故 0 起，仍有 5 项质量问题需要跟踪。"));
        elements.add(chart("Roche 七日计划与实际完成率", "line", seriesValues(
                days,
                List.of("计划完成率", "实际完成率"),
                List.of(List.of(62, 67, 72, 77, 82, 85, 88), List.of(60, 65, 70, 74, 78, 82, 86))
        )));
        elements.add(chart("七日现场人员投入", "bar", categoryValues(
                days, List.of(32, 35, 38, 40, 37, 41, 42), "现场人数")));
        elements.add(table(
                columns("code", "编号", "trade", "专业", "issue", "质量问题", "owner", "责任人", "status", "状态"),
                List.of(
                        row("code", "QC-0522", "trade", "消防", "issue", "套管防火封堵未完成", "owner", "Klaus Yao", "status", "逾期"),
                        row("code", "QC-0523", "trade", "暖通", "issue", "末端支架缺少横担", "owner", "Klaus Yao", "status", "逾期"),
                        row("code", "QC-0524", "trade", "电气", "issue", "桥架跨接线待补", "owner", "Li Yao", "status", "处理中"),
                        row("code", "QC-0525", "trade", "给排水", "issue", "管道坡度复测", "owner", "Chen Li", "status", "待复检"),
                        row("code", "QC-0526", "trade", "装修", "issue", "龙骨间距记录缺失", "owner", "Wang Lei", "status", "已分派")
                )));
        elements.add(markdown("**明日计划**：完成 3F 机电综合点位确认、2F 东侧隐蔽验收，以及空调设备到场验收。"));
        return preset("construction-daily", "施工日报", "七日进度、人员投入和质量问题清单", "#d97008",
                card("施工日报｜Roche 项目", "orange", elements));
    }

    private CardPreset risk() {
        List<Object> elements = new ArrayList<>();
        elements.add(markdown("**风险等级：高**\n\n三个项目共有 6 项高风险。Roche 的材料到场和 Siemens 的消防复检最可能影响本周节点。"));
        elements.add(chart("多项目风险等级对比", "bar", seriesValues(
                List.of("Roche", "Siemens", "XDL"),
                List.of("高风险", "中风险", "低风险"),
                List.of(List.of(3, 2, 1), List.of(4, 3, 2), List.of(2, 3, 4))
        )));
        elements.add(chart("风险类别占比", "pie", pieValues(
                List.of("进度", "材料", "质量", "安全"), List.of(5, 4, 3, 1))));
        elements.add(table(
                columns("project", "项目", "risk", "风险", "impact", "影响节点", "owner", "责任人", "deadline", "截止", "status", "状态"),
                List.of(
                        row("project", "Roche", "risk", "空调设备到场延迟", "impact", "3F 天花封板", "owner", "李敏", "deadline", "今日 18:00", "status", "跟进中"),
                        row("project", "Roche", "risk", "机电点位未确认", "impact", "隐蔽验收", "owner", "张伟", "deadline", "明日 10:00", "status", "未关闭"),
                        row("project", "Siemens", "risk", "消防材料复检滞后", "impact", "消防联调", "owner", "王强", "deadline", "今日 16:00", "status", "高风险"),
                        row("project", "Siemens", "risk", "暖通交叉作业冲突", "impact", "吊顶施工", "owner", "刘洋", "deadline", "明日 12:00", "status", "协调中"),
                        row("project", "XDL", "risk", "高压桥架方案变更", "impact", "电气送电", "owner", "陈晨", "deadline", "周三", "status", "待审批"),
                        row("project", "XDL", "risk", "供应商资料不完整", "impact", "设备验收", "owner", "孙悦", "deadline", "周四", "status", "已分派")
                )));
        elements.add(button("创建风险跟进项", "create_follow_up", "primary", Map.of("scope", "all-projects")));
        return preset("risk-alert", "风险提醒", "多项目风险对比、类别占比和明细跟踪", "#d92d20",
                card("项目风险提醒｜需要关注", "red", elements));
    }

    private CardPreset weekly(List<String> days) {
        List<Object> elements = new ArrayList<>();
        elements.add(markdown("**本周状态：总体可控**\n\nRoche 与 XDL 持续推进，Siemens 受消防材料复检影响落后计划 5 个百分点。"));
        elements.add(chart("三项目计划与实际完成率", "bar", seriesValues(
                List.of("Roche", "Siemens", "XDL"),
                List.of("计划完成率", "实际完成率"),
                List.of(List.of(88, 84, 93), List.of(86, 79, 92))
        )));
        elements.add(chart("七日新增与关闭问题趋势", "line", seriesValues(
                days,
                List.of("新增问题", "关闭问题"),
                List.of(List.of(5, 4, 6, 3, 4, 2, 3), List.of(2, 3, 4, 3, 5, 6, 7))
        )));
        elements.add(table(
                columns("project", "项目/工作流", "planned", "计划", "actual", "实际", "closed", "关闭问题", "focus", "下周重点"),
                List.of(
                        row("project", "Roche", "planned", "88%", "actual", "86%", "closed", "12", "focus", "设备到场 / 隐蔽验收"),
                        row("project", "Siemens", "planned", "84%", "actual", "79%", "closed", "8", "focus", "消防复检 / 交叉作业"),
                        row("project", "XDL", "planned", "93%", "actual", "92%", "closed", "10", "focus", "桥架审批 / 送电准备"),
                        row("project", "跨项目质量", "planned", "20 项", "actual", "18 项", "closed", "18", "focus", "关闭 2 项逾期缺陷"),
                        row("project", "跨项目资料", "planned", "15 份", "actual", "13 份", "closed", "13", "focus", "补齐签证和验收附件")
                )));
        elements.add(markdown("**下周重点**\n1. 锁定三项目材料到场计划\n2. 关闭所有逾期质量问题\n3. 周三前完成关键专业协调。"));
        return preset("weekly-summary", "周报摘要", "三项目完成率、问题趋势和周度汇总", "#6941c6",
                card("项目周报摘要", "purple", elements));
    }

    private CardPreset preset(String key, String label, String description, String color, Map<String, Object> card) {
        return new CardPreset(key, label, description, color, card);
    }

    private Map<String, Object> card(String title, String template, List<Object> elements) {
        return Map.of(
                "schema", "2.0",
                "config", Map.of("update_multi", true, "enable_forward", true),
                "header", Map.of(
                        "title", Map.of("tag", "plain_text", "content", title),
                        "template", template
                ),
                "body", Map.of("elements", List.copyOf(elements))
        );
    }

    private Map<String, Object> markdown(String content) {
        return Map.of("tag", "markdown", "content", content);
    }

    private Map<String, Object> table(List<Map<String, Object>> columns, List<Map<String, Object>> rows) {
        return Map.of(
                "tag", "table",
                "page_size", 10,
                "row_height", "high",
                "freeze_first_column", columns.size() > 4,
                "header_style", Map.of("bold", true, "background_style", "grey", "text_align", "left"),
                "columns", columns,
                "rows", rows
        );
    }

    private Map<String, Object> chart(String title, String type, List<Map<String, Object>> values) {
        Map<String, Object> spec = new LinkedHashMap<>();
        spec.put("type", type);
        spec.put("title", Map.of("text", title));
        spec.put("data", Map.of("values", values));
        if ("pie".equals(type)) {
            spec.put("categoryField", "label");
            spec.put("valueField", "value");
            spec.put("legends", Map.of("visible", true, "orient", "bottom"));
        } else {
            spec.put("xField", "label");
            spec.put("yField", "value");
            spec.put("seriesField", "series");
            spec.put("label", Map.of("visible", true));
            spec.put("legends", Map.of("visible", true, "orient", "bottom"));
        }
        return Map.of(
                "tag", "chart",
                "aspect_ratio", "pie".equals(type) ? "4:3" : "16:9",
                "color_theme", "brand",
                "preview", true,
                "chart_spec", spec
        );
    }

    private Map<String, Object> button(String label, String action, String type, Map<String, Object> extra) {
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("action", action);
        value.putAll(extra);
        return Map.of(
                "tag", "button",
                "text", Map.of("tag", "plain_text", "content", label),
                "type", type,
                "behaviors", List.of(Map.of("type", "callback", "value", value))
        );
    }

    private List<Map<String, Object>> columns(String... pairs) {
        List<Map<String, Object>> columns = new ArrayList<>();
        for (int index = 0; index < pairs.length; index += 2) {
            columns.add(Map.of(
                    "name", pairs[index],
                    "display_name", pairs[index + 1],
                    "data_type", "markdown",
                    "width", "auto",
                    "vertical_align", "top"
            ));
        }
        return List.copyOf(columns);
    }

    private Map<String, Object> row(Object... pairs) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (int index = 0; index < pairs.length; index += 2) {
            row.put(String.valueOf(pairs[index]), pairs[index + 1]);
        }
        return Map.copyOf(row);
    }

    private List<Map<String, Object>> categoryValues(List<String> labels, List<Integer> values, String series) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int index = 0; index < labels.size(); index++) {
            rows.add(Map.of("label", labels.get(index), "value", values.get(index), "series", series));
        }
        return List.copyOf(rows);
    }

    private List<Map<String, Object>> seriesValues(List<String> labels, List<String> series,
                                                    List<List<Integer>> values) {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int seriesIndex = 0; seriesIndex < series.size(); seriesIndex++) {
            for (int labelIndex = 0; labelIndex < labels.size(); labelIndex++) {
                rows.add(Map.of(
                        "label", labels.get(labelIndex),
                        "value", values.get(seriesIndex).get(labelIndex),
                        "series", series.get(seriesIndex)
                ));
            }
        }
        return List.copyOf(rows);
    }

    private List<Map<String, Object>> pieValues(List<String> labels, List<Integer> values) {
        return categoryValues(labels, values, "占比");
    }

    private List<String> lastSevenDays() {
        LocalDate today = LocalDate.now(CHINA);
        List<String> labels = new ArrayList<>();
        for (int offset = 6; offset >= 0; offset--) {
            labels.add(today.minusDays(offset).format(DAY));
        }
        return List.copyOf(labels);
    }
}
