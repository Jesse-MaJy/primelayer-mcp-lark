package com.larkconnect.agent.ai;

public enum PromptDomain {
    GLOBAL,
    SAFETY,
    QUALITY,
    PROGRESS,
    RISK;

    public static PromptDomain detect(String text) {
        if (text == null) return GLOBAL;
        if (contains(text, "安全", "隐患", "作业票", "事故")) return SAFETY;
        if (contains(text, "质量", "缺陷", "验收", "整改")) return QUALITY;
        if (contains(text, "进度", "计划", "工期", "完成率")) return PROGRESS;
        if (contains(text, "风险", "逾期", "预警")) return RISK;
        return GLOBAL;
    }

    private static boolean contains(String value, String... terms) {
        for (String term : terms) if (value.contains(term)) return true;
        return false;
    }
}
