const PptxGenJS = require("pptxgenjs");
const path = require("path");

const pptx = new PptxGenJS();
pptx.layout = "LAYOUT_WIDE"; // 13.33 x 7.5 inches
pptx.author = "Lark Connect Team";
pptx.company = "Primelayer";
pptx.subject = "Lark Connect Agent Gateway 项目分析与技术路线";

// ── Color palette ──
const C = {
  primary: "0F4C81",      // deep blue
  accent: "00A6A6",       // teal
  accent2: "F5A623",      // orange
  danger: "E84855",       // red
  success: "2D9D5F",      // green
  dark: "1A1A2E",         // near-black
  bg: "FFFFFF",
  lightBg: "F0F4F8",
  lightGray: "E8EDF2",
  midGray: "6B7B8D",
  textDark: "2C3E50",
  textLight: "FFFFFF",
  textMuted: "8B9DAF",
};

// ── Helper functions ──
function addSlideBg(slide, color = C.bg) {
  slide.background = { color };
}

function addHeaderBar(slide, title, subtitle) {
  // Top color bar
  slide.addShape("rect", { x: 0, y: 0, w: 13.33, h: 0.08, fill: { color: C.primary } });
  slide.addShape("rect", { x: 0, y: 0.08, w: 13.33, h: 0.03, fill: { color: C.accent } });

  // Title
  slide.addText(title, {
    x: 0.6, y: 0.25, w: 10, h: 0.5,
    fontSize: 26, fontFace: "Arial", bold: true, color: C.primary,
  });

  if (subtitle) {
    slide.addText(subtitle, {
      x: 0.6, y: 0.72, w: 12, h: 0.3,
      fontSize: 13, fontFace: "Arial", color: C.midGray, italic: true,
    });
  }
}

function addFooter(slide, pageNum) {
  slide.addShape("rect", { x: 0, y: 7.35, w: 13.33, h: 0.02, fill: { color: C.lightGray } });
  slide.addText("Lark Connect Agent Gateway", {
    x: 0.6, y: 7.05, w: 6, h: 0.3,
    fontSize: 9, fontFace: "Arial", color: C.textMuted,
  });
  slide.addText(`${pageNum}`, {
    x: 12.4, y: 7.05, w: 0.6, h: 0.3,
    fontSize: 9, fontFace: "Arial", color: C.textMuted, align: "right",
  });
}

function addCard(slide, x, y, w, h, fillColor, borderColor) {
  slide.addShape("roundRect", {
    x, y, w, h,
    fill: { color: fillColor },
    line: borderColor ? { color: borderColor, width: 1 } : undefined,
    rectRadius: 0.08,
  });
}

// ══════════════════════════════════════════════
// SLIDE 1: Title
// ══════════════════════════════════════════════
{
  const slide = pptx.addSlide();
  addSlideBg(slide, C.dark);

  // Decorative shapes
  slide.addShape("rect", { x: 0, y: 0, w: 13.33, h: 0.12, fill: { color: C.primary } });
  slide.addShape("rect", { x: 0, y: 0.12, w: 13.33, h: 0.06, fill: { color: C.accent } });
  slide.addShape("rect", { x: 0, y: 7.32, w: 13.33, h: 0.06, fill: { color: C.accent } });
  slide.addShape("rect", { x: 0, y: 7.38, w: 13.33, h: 0.12, fill: { color: C.primary } });

  // Accent circles
  slide.addShape("ellipse", { x: 10.5, y: 0.8, w: 2.5, h: 2.5, fill: { color: C.primary, transparency: 75 } });
  slide.addShape("ellipse", { x: 11.2, y: 1.5, w: 1.5, h: 1.5, fill: { color: C.accent, transparency: 70 } });

  slide.addText("Lark Connect", {
    x: 0.8, y: 1.8, w: 11, h: 0.8,
    fontSize: 44, fontFace: "Arial", bold: true, color: C.textLight,
  });

  slide.addText("Agent Gateway", {
    x: 0.8, y: 2.5, w: 11, h: 0.6,
    fontSize: 30, fontFace: "Arial", color: C.accent,
  });

  slide.addShape("rect", { x: 0.8, y: 3.2, w: 3.5, h: 0.04, fill: { color: C.accent2 } });

  slide.addText("项目分析  |  优化建议  |  方法论一致性设计  |  技术路线", {
    x: 0.8, y: 3.4, w: 11, h: 0.5,
    fontSize: 16, fontFace: "Arial", color: C.textMuted,
  });

  slide.addText([
    { text: "DeepSeek + Primelayer MCP + Feishu\n", options: { fontSize: 14, color: C.textMuted, breakLine: true } },
    { text: "Java 17 / Spring Boot 3.3.7  ·  Python FastAPI / LangGraph  ·  Vue 3 / Ant Design Vue 4", options: { fontSize: 11, color: C.textMuted } },
  ], {
    x: 0.8, y: 4.3, w: 11, h: 0.8,
    fontFace: "Arial",
  });

  slide.addText("2026-07-01", {
    x: 0.8, y: 6.2, w: 4, h: 0.4,
    fontSize: 13, fontFace: "Arial", color: C.accent,
  });

  addFooter(slide, 1);
}

// ══════════════════════════════════════════════
// SLIDE 2: Agenda
// ══════════════════════════════════════════════
{
  const slide = pptx.addSlide();
  addSlideBg(slide);
  addHeaderBar(slide, "目录", "Agenda");

  const items = [
    { num: "01", title: "项目定位与核心价值", desc: "打通 Primelayer 与飞书，自然语言查询项目数据" },
    { num: "02", title: "系统架构与模块职责", desc: "Java 安全边界 + Python 智能层 + Vue 管理后台" },
    { num: "03", title: "核心数据流与 Agent 状态机", desc: "5 步 LangGraph 流水线，4+1 技能路由" },
    { num: "04", title: "当前问题与优化建议", desc: "P0/P1/P2 分级问题清单与修复方向" },
    { num: "05", title: "方法论一致性设计方案", desc: "同一问题不同时间 → 一致结论的保障机制" },
    { num: "06", title: "技术路线与演进规划", desc: "MVP → 工程化 → 通用 Agent 平台三阶段" },
  ];

  items.forEach((item, i) => {
    const y = 1.3 + i * 0.95;
    addCard(slide, 0.6, y, 12.1, 0.8, C.lightBg, C.lightGray);

    slide.addText(item.num, {
      x: 0.8, y: y + 0.1, w: 0.8, h: 0.6,
      fontSize: 24, fontFace: "Arial", bold: true, color: C.primary, align: "center", valign: "middle",
    });

    slide.addShape("rect", { x: 1.6, y: y + 0.12, w: 0.03, h: 0.56, fill: { color: C.accent } });

    slide.addText(item.title, {
      x: 1.8, y: y + 0.08, w: 5, h: 0.35,
      fontSize: 14, fontFace: "Arial", bold: true, color: C.textDark, valign: "middle",
    });

    slide.addText(item.desc, {
      x: 1.8, y: y + 0.4, w: 10.5, h: 0.3,
      fontSize: 11, fontFace: "Arial", color: C.midGray, valign: "middle",
    });
  });

  addFooter(slide, 2);
}

// ══════════════════════════════════════════════
// SLIDE 3: Project Positioning
// ══════════════════════════════════════════════
{
  const slide = pptx.addSlide();
  addSlideBg(slide);
  addHeaderBar(slide, "项目定位与核心价值", "01 · What does this project do?");

  // Left: identity chain
  addCard(slide, 0.6, 1.3, 5.5, 2.6, C.lightBg, C.primary);

  slide.addText("身份与权限链路", {
    x: 0.8, y: 1.4, w: 5, h: 0.4,
    fontSize: 14, fontFace: "Arial", bold: true, color: C.primary,
  });

  const chain = [
    { label: "feishu_open_id", color: C.accent },
    { label: "primelayer_user_id", color: C.accent2 },
    { label: "project_id", color: C.accent },
    { label: "mcp_token", color: C.danger },
  ];

  chain.forEach((node, i) => {
    const ny = 1.9 + i * 0.5;
    slide.addShape("roundRect", {
      x: 0.9, y: ny, w: 4.9, h: 0.4,
      fill: { color: node.color },
      rectRadius: 0.05,
    });
    slide.addText(node.label, {
      x: 0.9, y: ny, w: 4.9, h: 0.4,
      fontSize: 12, fontFace: "Courier New", bold: true, color: C.textLight, align: "center", valign: "middle",
    });
    if (i < chain.length - 1) {
      slide.addText("▼", {
        x: 2.8, y: ny + 0.35, w: 1, h: 0.2,
        fontSize: 10, color: C.midGray, align: "center",
      });
    }
  });

  // Right: key facts
  addCard(slide, 6.4, 1.3, 6.3, 5.4, C.lightBg, C.lightGray);

  slide.addText("核心事实", {
    x: 6.6, y: 1.4, w: 5, h: 0.4,
    fontSize: 14, fontFace: "Arial", bold: true, color: C.primary,
  });

  const facts = [
    "打通 Primelayer（地产科技项目管理系统）与飞书",
    "飞书用户用自然语言查询项目数据、待办、风险",
    "底层大模型：DeepSeek",
    "支持私聊单项目 / 私聊跨项目 / 群聊项目上下文查询",
    "RabbitMQ 异步任务执行 + 飞书异步回复",
    "三级审计：任务 / 工具调用 / 模型调用",
    "MCP Token AES-GCM 密文存储，永不传入智能层",
    "一期目标：带管理后台的 Agent Gateway MVP",
    "后续演进：通用 Agent 平台（多入口、多步工具、主动监控）",
  ];

  facts.forEach((fact, i) => {
    slide.addShape("ellipse", {
      x: 6.7, y: 1.95 + i * 0.5, w: 0.12, h: 0.12,
      fill: { color: C.accent },
    });
    slide.addText(fact, {
      x: 6.95, y: 1.88 + i * 0.5, w: 5.5, h: 0.35,
      fontSize: 11, fontFace: "Arial", color: C.textDark, valign: "middle",
    });
  });

  // Bottom: value proposition
  addCard(slide, 0.6, 4.1, 5.5, 2.6, C.dark, null);

  slide.addText("核心价值", {
    x: 0.8, y: 4.2, w: 5, h: 0.4,
    fontSize: 14, fontFace: "Arial", bold: true, color: C.accent,
  });

  const values = [
    { icon: "⚡", text: "自然语言即查询入口，降低 Primelayer 使用门槛" },
    { icon: "🔒", text: "Java 后端是唯一安全边界，Token 不出后端" },
    { icon: "📊", text: "三级审计齐全，每次查询可追溯可回放" },
  ];

  values.forEach((v, i) => {
    slide.addText(v.icon, {
      x: 0.8, y: 4.7 + i * 0.6, w: 0.4, h: 0.4,
      fontSize: 16, align: "center", valign: "middle",
    });
    slide.addText(v.text, {
      x: 1.3, y: 4.7 + i * 0.6, w: 4.5, h: 0.4,
      fontSize: 11, fontFace: "Arial", color: C.textLight, valign: "middle",
    });
  });

  addFooter(slide, 3);
}

// ══════════════════════════════════════════════
// SLIDE 4: Architecture
// ══════════════════════════════════════════════
{
  const slide = pptx.addSlide();
  addSlideBg(slide);
  addHeaderBar(slide, "系统架构与模块职责", "02 · Three-Module Architecture");

  // Three module cards
  const modules = [
    {
      x: 0.6, color: C.primary, icon: "🛡️",
      title: "backend",
      subtitle: "Java 17 / Spring Boot 3.3.7",
      role: "安全边界",
      items: [
        "飞书事件接收与解析",
        "权限校验与用户绑定",
        "MCP Token 解密与调用",
        "RabbitMQ 异步任务编排",
        "DeepSeek 集成（plan/summarize）",
        "三级审计日志写入",
        "REST API 管理后台",
      ],
    },
    {
      x: 5.05, color: C.accent, icon: "🧠",
      title: "agent-service",
      subtitle: "Python / FastAPI / LangGraph",
      role: "智能层",
      items: [
        "技能路由（关键词匹配分类）",
        "项目范围选择",
        "MCP 工具调用规划",
        "答案摘要生成",
        "质量检查",
        "只收脱敏上下文",
        "MCP Token 永不进入此服务",
      ],
    },
    {
      x: 9.5, color: C.accent2, icon: "🖥️",
      title: "admin-web",
      subtitle: "Vue 3 / Ant Design Vue 4 / Vite 6",
      role: "管理后台",
      items: [
        "用户绑定管理",
        "项目 MCP Token 管理",
        "飞书群-项目绑定",
        "异步任务监控",
        "三级审计日志可视化",
        "飞书消息记录",
        "测试中心 / 人员配置",
      ],
    },
  ];

  modules.forEach((mod) => {
    addCard(slide, mod.x, 1.3, 3.8, 5.5, C.lightBg, mod.color);

    // Header strip
    slide.addShape("roundRect", {
      x: mod.x, y: 1.3, w: 3.8, h: 0.7,
      fill: { color: mod.color },
      rectRadius: 0.08,
    });
    slide.addText(mod.icon + "  " + mod.title, {
      x: mod.x + 0.15, y: 1.32, w: 3.5, h: 0.35,
      fontSize: 14, fontFace: "Arial", bold: true, color: C.textLight, valign: "middle",
    });
    slide.addText(mod.subtitle, {
      x: mod.x + 0.15, y: 1.63, w: 3.5, h: 0.3,
      fontSize: 9, fontFace: "Arial", color: C.textLight, valign: "middle",
    });

    // Role badge
    slide.addShape("roundRect", {
      x: mod.x + 0.2, y: 2.15, w: 1.6, h: 0.35,
      fill: { color: mod.color, transparency: 80 },
      line: { color: mod.color, width: 1 },
      rectRadius: 0.05,
    });
    slide.addText(mod.role, {
      x: mod.x + 0.2, y: 2.15, w: 1.6, h: 0.35,
      fontSize: 10, fontFace: "Arial", bold: true, color: mod.color, align: "center", valign: "middle",
    });

    mod.items.forEach((item, i) => {
      slide.addText("•", {
        x: mod.x + 0.2, y: 2.65 + i * 0.5, w: 0.2, h: 0.35,
        fontSize: 12, color: mod.color, valign: "middle",
      });
      slide.addText(item, {
        x: mod.x + 0.45, y: 2.65 + i * 0.5, w: 3.1, h: 0.35,
        fontSize: 10, fontFace: "Arial", color: C.textDark, valign: "middle",
      });
    });
  });

  // Connection arrows
  slide.addText("←→ HTTP / JSON", {
    x: 4.5, y: 3.8, w: 1, h: 0.3,
    fontSize: 8, fontFace: "Arial", color: C.midGray, align: "center", italic: true,
  });
  slide.addText("←→ HTTP / JSON", {
    x: 8.9, y: 3.8, w: 1, h: 0.3,
    fontSize: 8, fontFace: "Arial", color: C.midGray, align: "center", italic: true,
  });

  addFooter(slide, 4);
}

// ══════════════════════════════════════════════
// SLIDE 5: Core Data Flow
// ══════════════════════════════════════════════
{
  const slide = pptx.addSlide();
  addSlideBg(slide);
  addHeaderBar(slide, "核心数据流", "03 · End-to-End Message Processing");

  const steps = [
    { label: "飞书消息", desc: "FeishuEventController 接收", color: C.accent, x: 0.6 },
    { label: "RabbitMQ", desc: "异步入队", color: C.accent2, x: 2.7 },
    { label: "AgentWorker", desc: "消费消息", color: C.primary, x: 4.8 },
    { label: "TokenResolver", desc: "解密 MCP Token", color: C.danger, x: 6.9 },
    { label: "AgentOrchestrator", desc: "编排核心流程", color: C.primary, x: 9.0 },
    { label: "agent-service", desc: "技能路由 + 工具规划", color: C.accent, x: 11.1 },
  ];

  // Draw flow boxes
  steps.forEach((step, i) => {
    slide.addShape("roundRect", {
      x: step.x, y: 1.5, w: 1.8, h: 0.9,
      fill: { color: step.color },
      rectRadius: 0.08,
    });
    slide.addText(step.label, {
      x: step.x, y: 1.55, w: 1.8, h: 0.4,
      fontSize: 10, fontFace: "Arial", bold: true, color: C.textLight, align: "center", valign: "middle",
    });
    slide.addText(step.desc, {
      x: step.x, y: 1.92, w: 1.8, h: 0.35,
      fontSize: 8, fontFace: "Arial", color: C.textLight, align: "center", valign: "middle",
    });
    if (i < steps.length - 1) {
      slide.addText("→", {
        x: step.x + 1.75, y: 1.65, w: 0.4, h: 0.5,
        fontSize: 18, color: C.midGray, align: "center", valign: "middle", bold: true,
      });
    }
  });

  // Return flow
  slide.addText("← toolCalls", {
    x: 9.0, y: 2.6, w: 3.8, h: 0.3,
    fontSize: 9, fontFace: "Arial", color: C.midGray, align: "center", italic: true,
  });

  // Second row: MCP call + summarize
  const steps2 = [
    { label: "McpAdapter", desc: "携 Token 调 Primelayer MCP", color: C.danger, x: 3.5 },
    { label: "工具结果回传", desc: "agent-service Summarization", color: C.accent, x: 6.2 },
    { label: "FeishuClient", desc: "异步回复飞书", color: C.accent2, x: 8.9 },
    { label: "AuditService", desc: "写三级审计日志", color: C.primary, x: 11.1 },
  ];

  steps2.forEach((step, i) => {
    slide.addShape("roundRect", {
      x: step.x, y: 3.1, w: 1.8, h: 0.9,
      fill: { color: step.color, transparency: 15 },
      line: { color: step.color, width: 1.5 },
      rectRadius: 0.08,
    });
    slide.addText(step.label, {
      x: step.x, y: 3.15, w: 1.8, h: 0.4,
      fontSize: 10, fontFace: "Arial", bold: true, color: step.color, align: "center", valign: "middle",
    });
    slide.addText(step.desc, {
      x: step.x, y: 3.52, w: 1.8, h: 0.35,
      fontSize: 8, fontFace: "Arial", color: C.midGray, align: "center", valign: "middle",
    });
    if (i < steps2.length - 1) {
      slide.addText("→", {
        x: step.x + 1.75, y: 3.25, w: 0.4, h: 0.5,
        fontSize: 18, color: C.midGray, align: "center", valign: "middle", bold: true,
      });
    }
  });

  // Key features box
  addCard(slide, 0.6, 4.4, 12.1, 2.4, C.lightBg, C.lightGray);

  slide.addText("关键设计特征", {
    x: 0.8, y: 4.5, w: 5, h: 0.35,
    fontSize: 13, fontFace: "Arial", bold: true, color: C.primary,
  });

  const features = [
    { title: "异步解耦", desc: "飞书消息 → RabbitMQ → Worker，消息接收与处理解耦，避免超时" },
    { title: "安全边界", desc: "MCP Token 仅在 Java 后端解密使用，agent-service 只收脱敏上下文（openId / 项目列表 / 工具定义）" },
    { title: "多轮编排", desc: "MAX_AGENT_SERVICE_ROUNDS = 4，支持工具调用 → 结果回传 → 再规划的循环" },
    { title: "降级策略", desc: "agent-service 不可用时回退到 Legacy DeepSeek plan/summarize 链路" },
    { title: "实时验证", desc: "MCP 配置状态检查支持实时 listTools 验证 Token 有效性" },
  ];

  features.forEach((f, i) => {
    const col = i < 3 ? 0 : 1;
    const row = i % 3;
    const fx = col === 0 ? 0.8 : 6.6;
    const fy = 4.9 + row * 0.55;

    slide.addText("▸", {
      x: fx, y: fy, w: 0.2, h: 0.35,
      fontSize: 11, color: C.accent, valign: "middle",
    });
    slide.addText(f.title + "：", {
      x: fx + 0.2, y: fy, w: 1.3, h: 0.35,
      fontSize: 10, fontFace: "Arial", bold: true, color: C.primary, valign: "middle",
    });
    slide.addText(f.desc, {
      x: fx + 1.5, y: fy, w: 4.2, h: 0.35,
      fontSize: 9, fontFace: "Arial", color: C.textDark, valign: "middle",
    });
  });

  addFooter(slide, 5);
}

// ══════════════════════════════════════════════
// SLIDE 6: Agent State Machine
// ══════════════════════════════════════════════
{
  const slide = pptx.addSlide();
  addSlideBg(slide);
  addHeaderBar(slide, "Agent 状态机与技能体系", "03 · LangGraph 5-Step Pipeline + 5 Skills");

  // State machine flow
  const states = [
    { name: "classify_skill", desc: "关键词匹配\n技能分类", color: C.accent },
    { name: "select_project_scope", desc: "确定查询\n项目范围", color: C.accent2 },
    { name: "plan_tool_calls", desc: "选择只读工具\n构建调用参数", color: C.primary },
    { name: "summarize_answer", desc: "模型摘要\n或确定性摘要", color: C.accent },
    { name: "quality_check", desc: "追加数据范围\n声明", color: C.success },
  ];

  states.forEach((state, i) => {
    const sx = 0.6 + i * 2.55;
    slide.addShape("roundRect", {
      x: sx, y: 1.3, w: 2.2, h: 1.1,
      fill: { color: state.color },
      rectRadius: 0.1,
    });
    slide.addText(state.name, {
      x: sx, y: 1.35, w: 2.2, h: 0.35,
      fontSize: 10, fontFace: "Courier New", bold: true, color: C.textLight, align: "center", valign: "middle",
    });
    slide.addText(state.desc, {
      x: sx, y: 1.68, w: 2.2, h: 0.65,
      fontSize: 8, fontFace: "Arial", color: C.textLight, align: "center", valign: "middle",
    });
    if (i < states.length - 1) {
      slide.addText("→", {
        x: sx + 2.15, y: 1.5, w: 0.5, h: 0.7,
        fontSize: 22, color: C.midGray, align: "center", valign: "middle", bold: true,
      });
    }
  });

  // Skills table
  slide.addText("技能体系（5 个 SkillDefinition）", {
    x: 0.6, y: 2.7, w: 8, h: 0.35,
    fontSize: 13, fontFace: "Arial", bold: true, color: C.primary,
  });

  const skills = [
    { id: "project_report", name: "项目报告与施工情况", triggers: "施工日报、质量安全、安全隐患、进度", tools: "get_base_form_info, match_form_resource, query_form_data_list...", color: C.primary },
    { id: "project_status_qa", name: "项目状态问答", triggers: "项目怎么样、当前进度、健康度", tools: "query_project_health, get_project_status...", color: C.accent },
    { id: "task_risk_qa", name: "任务风险问答", triggers: "逾期、风险、待办、负责人、阻塞", tools: "query_tasks, search_tasks, get_task_risks...", color: C.accent2 },
    { id: "weekly_report", name: "日报周报生成", triggers: "周报、日报、总结、本周、下周", tools: "query_project_health, query_tasks, get_report...", color: C.success },
    { id: "general_mcp_qa", name: "通用 MCP 问答（兜底）", triggers: "查询、看看、帮我分析", tools: "所有 get_/list_/query_/search_ 前缀工具", color: C.midGray },
  ];

  // Table header
  const tableRows = [["技能 ID", "技能名称", "触发关键词", "允许的只读工具"]];
  skills.forEach(s => {
    tableRows.push([s.id, s.name, s.triggers, s.tools]);
  });

  slide.addTable(tableRows, {
    x: 0.6, y: 3.1, w: 12.1,
    colW: [2.0, 2.5, 3.5, 4.1],
    fontSize: 9,
    fontFace: "Arial",
    border: { type: "solid", color: C.lightGray, pt: 1 },
    rowH: [0.35, 0.5, 0.5, 0.5, 0.5, 0.5],
    valign: "middle",
    color: C.textDark,
    align: "left",
  });

  // Header row styling
  slide.addShape("rect", {
    x: 0.6, y: 3.1, w: 12.1, h: 0.35,
    fill: { color: C.primary },
  });
  slide.addText([
    { text: "技能 ID", options: { x: 0.7, w: 1.9, bold: true } },
    { text: "技能名称", options: { x: 2.6, w: 2.4, bold: true } },
    { text: "触发关键词", options: { x: 5.0, w: 3.4, bold: true } },
    { text: "允许的只读工具", options: { x: 8.4, w: 4.0, bold: true } },
  ].map(t => ({ ...t, fontSize: 9, color: C.textLight, fontFace: "Arial" })).map(t => ({
    text: t.text, options: { x: t.options.x, y: 3.1, w: t.options.w, h: 0.35, fontSize: 9, color: C.textLight, bold: true, valign: "middle" }
  })));

  // Note at bottom
  addCard(slide, 0.6, 6.0, 12.1, 0.8, C.dark, null);
  slide.addText([
    { text: "关键约束：", options: { bold: true, color: C.accent2, fontSize: 10 } },
    { text: "  只选只读工具（get_/list_/query_/search_ 前缀）·  关键词匹配分类（确定性逻辑）·  摘要可选模型（temperature=0.1），无 API Key 时走确定性摘要", options: { color: C.textLight, fontSize: 10 } },
  ], {
    x: 0.8, y: 6.05, w: 11.7, h: 0.7,
    fontFace: "Arial", valign: "middle",
  });

  addFooter(slide, 6);
}

// ══════════════════════════════════════════════
// SLIDE 7: Database Schema
// ══════════════════════════════════════════════
{
  const slide = pptx.addSlide();
  addSlideBg(slide);
  addHeaderBar(slide, "数据库设计（MySQL 8 + Flyway）", "03 · 9 Tables, V1-V5 Migrations");

  const tables = [
    { name: "admin_user", desc: "管理员账户", group: "管理", color: C.primary },
    { name: "user_binding", desc: "飞书↔Primelayer\n用户绑定", group: "身份", color: C.accent },
    { name: "project_mcp_token", desc: "项目级 MCP Token\n（密文存储）", group: "安全", color: C.danger },
    { name: "feishu_chat_project_binding", desc: "飞书群↔项目\n绑定", group: "身份", color: C.accent },
    { name: "agent_task", desc: "异步任务", group: "任务", color: C.accent2 },
    { name: "agent_audit_log", desc: "任务级审计日志", group: "审计", color: C.success },
    { name: "agent_tool_call_log", desc: "工具调用审计\n日志", group: "审计", color: C.success },
    { name: "agent_model_call_log", desc: "模型调用审计\n日志", group: "审计", color: C.success },
    { name: "system_config", desc: "系统配置\n（含 AES 主密钥）", group: "配置", color: C.midGray },
  ];

  // Group legend
  const groups = {};
  tables.forEach(t => {
    if (!groups[t.group]) groups[t.group] = [];
    groups[t.group].push(t);
  });

  let yPos = 1.4;
  Object.entries(groups).forEach(([group, groupTables]) => {
    slide.addText(group, {
      x: 0.6, y: yPos, w: 1.5, h: 0.3,
      fontSize: 11, fontFace: "Arial", bold: true, color: C.midGray,
    });

    groupTables.forEach((t, i) => {
      const xPos = 2.2 + i * 2.85;

      slide.addShape("roundRect", {
        x: xPos, y: yPos - 0.05, w: 2.6, h: 1.0,
        fill: { color: C.lightBg },
        line: { color: t.color, width: 1.5 },
        rectRadius: 0.06,
      });

      slide.addShape("rect", {
        x: xPos, y: yPos - 0.05, w: 2.6, h: 0.3,
        fill: { color: t.color },
      });

      slide.addText(t.name, {
        x: xPos, y: yPos - 0.05, w: 2.6, h: 0.3,
        fontSize: 9, fontFace: "Courier New", bold: true, color: C.textLight, align: "center", valign: "middle",
      });

      slide.addText(t.desc, {
        x: xPos + 0.1, y: yPos + 0.28, w: 2.4, h: 0.65,
        fontSize: 9, fontFace: "Arial", color: C.textDark, align: "center", valign: "middle",
      });
    });

    yPos += 1.25;
  });

  // Flyway info
  addCard(slide, 0.6, 5.7, 12.1, 1.1, C.dark, null);
  slide.addText("Flyway 迁移历史", {
    x: 0.8, y: 5.78, w: 3, h: 0.3,
    fontSize: 11, fontFace: "Arial", bold: true, color: C.accent,
  });
  slide.addText([
    { text: "V1 ", options: { bold: true, color: C.accent2 } },
    { text: "初始化 9 张表    ", options: { color: C.textLight } },
    { text: "V2 ", options: { bold: true, color: C.accent2 } },
    { text: "user_binding 增加 person_name    ", options: { color: C.textLight } },
    { text: "V3 ", options: { bold: true, color: C.accent2 } },
    { text: "project_mcp_token 增加验证状态字段    ", options: { color: C.textLight } },
    { text: "V4 ", options: { bold: true, color: C.accent2 } },
    { text: "增加 owner 字段    ", options: { color: C.textLight } },
    { text: "V5 ", options: { bold: true, color: C.accent2 } },
    { text: "Token 去重", options: { color: C.textLight } },
  ], {
    x: 0.8, y: 6.1, w: 11.7, h: 0.6,
    fontSize: 10, fontFace: "Arial", valign: "middle",
  });

  addFooter(slide, 7);
}

// ══════════════════════════════════════════════
// SLIDE 8: Issues & Optimization
// ══════════════════════════════════════════════
{
  const slide = pptx.addSlide();
  addSlideBg(slide);
  addHeaderBar(slide, "当前问题与优化建议", "04 · P0 / P1 / P2 Priority Issues");

  // P0
  addCard(slide, 0.6, 1.3, 12.1, 1.65, "FFF5F5", C.danger);
  slide.addShape("roundRect", {
    x: 0.8, y: 1.4, w: 0.7, h: 0.35,
    fill: { color: C.danger },
    rectRadius: 0.05,
  });
  slide.addText("P0", {
    x: 0.8, y: 1.4, w: 0.7, h: 0.35,
    fontSize: 12, fontFace: "Arial", bold: true, color: C.textLight, align: "center", valign: "middle",
  });
  slide.addText("必须修复 — 上生产前阻断", {
    x: 1.6, y: 1.4, w: 5, h: 0.35,
    fontSize: 11, fontFace: "Arial", bold: true, color: C.danger, valign: "middle",
  });

  const p0Items = [
    "pom.xml maven-compiler-plugin 配置 source/target=5（Java 5！），与 java.version=17 严重矛盾 → 删除或改为 17",
    "MCP Token AES-GCM 主密钥存于 system_config 表 → 上生产前迁移到环境变量 / KMS",
  ];
  p0Items.forEach((item, i) => {
    slide.addText("⚠", {
      x: 0.85, y: 1.85 + i * 0.5, w: 0.3, h: 0.35,
      fontSize: 11, color: C.danger, valign: "middle",
    });
    slide.addText(item, {
      x: 1.2, y: 1.85 + i * 0.5, w: 11.2, h: 0.35,
      fontSize: 10, fontFace: "Arial", color: C.textDark, valign: "middle",
    });
  });

  // P1
  addCard(slide, 0.6, 3.1, 12.1, 2.15, "FFFBF0", C.accent2);
  slide.addShape("roundRect", {
    x: 0.8, y: 3.2, w: 0.7, h: 0.35,
    fill: { color: C.accent2 },
    rectRadius: 0.05,
  });
  slide.addText("P1", {
    x: 0.8, y: 3.2, w: 0.7, h: 0.35,
    fontSize: 12, fontFace: "Arial", bold: true, color: C.textLight, align: "center", valign: "middle",
  });
  slide.addText("建议尽快处理 — 影响工程化质量", {
    x: 1.6, y: 3.2, w: 6, h: 0.35,
    fontSize: 11, fontFace: "Arial", bold: true, color: C.accent2, valign: "middle",
  });

  const p1Items = [
    "大量改动未提交：初始 commit 后所有迭代堆在工作区 → 按功能分批 commit",
    "默认管理员凭据 admin/admin123 → 生产环境必须强改",
    "DeepSeek 模型名不一致：.env.example 为 deepseek-v4-pro，application.yml 默认 deepseek-chat → 统一",
    "测试覆盖偏低：backend 仅 3 个测试类，agent-service 仅 1 个 → 核心编排与 MCP 适配补测试",
  ];
  p1Items.forEach((item, i) => {
    slide.addText("●", {
      x: 0.85, y: 3.65 + i * 0.38, w: 0.3, h: 0.3,
      fontSize: 10, color: C.accent2, valign: "middle",
    });
    slide.addText(item, {
      x: 1.2, y: 3.65 + i * 0.38, w: 11.2, h: 0.3,
      fontSize: 10, fontFace: "Arial", color: C.textDark, valign: "middle",
    });
  });

  // P2
  addCard(slide, 0.6, 5.4, 12.1, 1.4, C.lightBg, C.lightGray);
  slide.addShape("roundRect", {
    x: 0.8, y: 5.5, w: 0.7, h: 0.35,
    fill: { color: C.midGray },
    rectRadius: 0.05,
  });
  slide.addText("P2", {
    x: 0.8, y: 5.5, w: 0.7, h: 0.35,
    fontSize: 12, fontFace: "Arial", bold: true, color: C.textLight, align: "center", valign: "middle",
  });
  slide.addText("持续改进 — 功能与体验优化", {
    x: 1.6, y: 5.5, w: 6, h: 0.35,
    fontSize: 11, fontFace: "Arial", bold: true, color: C.midGray, valign: "middle",
  });

  const p2Items = [
    "Agent 智能性有限：技能分类与工具选择均为关键词匹配 → 引入 LLM-based 路由",
    "DebugController 暴露调试端点 → 生产环境禁用或权限收敛",
    ".DS_Store 散落 → 确认 .gitignore 已忽略",
  ];
  p2Items.forEach((item, i) => {
    slide.addText("◆", {
      x: 0.85, y: 5.95 + i * 0.32, w: 0.3, h: 0.28,
      fontSize: 9, color: C.midGray, valign: "middle",
    });
    slide.addText(item, {
      x: 1.2, y: 5.95 + i * 0.32, w: 11.2, h: 0.28,
      fontSize: 10, fontFace: "Arial", color: C.textDark, valign: "middle",
    });
  });

  addFooter(slide, 8);
}

// ══════════════════════════════════════════════
// SLIDE 9: Consistency Problem Analysis
// ══════════════════════════════════════════════
{
  const slide = pptx.addSlide();
  addSlideBg(slide);
  addHeaderBar(slide, "方法论一致性：问题分析", "05 · Why same question yields different answers?");

  // Problem statement
  addCard(slide, 0.6, 1.3, 12.1, 0.9, C.dark, null);
  slide.addText([
    { text: "核心诉求：", options: { bold: true, color: C.accent2, fontSize: 12 } },
    { text: "  同一个问题在不同时间提问，Agent 系统应得出一致的结论（方法论一致、推理路径一致、输出结构一致）", options: { color: C.textLight, fontSize: 12 } },
  ], {
    x: 0.8, y: 1.35, w: 11.7, h: 0.8,
    fontFace: "Arial", valign: "middle",
  });

  // Analysis: What affects consistency
  slide.addText("影响一致性的因素分析", {
    x: 0.6, y: 2.4, w: 8, h: 0.35,
    fontSize: 13, fontFace: "Arial", bold: true, color: C.primary,
  });

  const factors = [
    {
      stage: "classify_skill",
      current: "关键词匹配评分（确定性）",
      consistent: true,
      color: C.success,
      risk: "同义改写 / 口语化表达可能命中不同技能",
    },
    {
      stage: "select_project_scope",
      current: "关键词 + 群聊上下文（确定性）",
      consistent: true,
      color: C.success,
      risk: "项目名称模糊匹配可能选错范围",
    },
    {
      stage: "plan_tool_calls",
      current: "关键词评分选工具（确定性）",
      consistent: true,
      color: C.success,
      risk: "工具评分相同时 max() 取第一个，结果依赖列表顺序",
    },
    {
      stage: "summarize_answer",
      current: "DeepSeek temperature=0.1（非确定性！）",
      consistent: false,
      color: C.danger,
      risk: "即使 temperature=0.1，同一输入仍可能产出不同文本；无 API Key 时走确定性摘要",
    },
    {
      stage: "MCP 工具返回",
      current: "底层数据可能变化",
      consistent: false,
      color: C.accent2,
      risk: "项目数据随时间变化是正常的，但结论结构应一致",
    },
  ];

  // Table
  const tableData = [
    ["流水线阶段", "当前实现", "确定性", "风险点"],
    ...factors.map(f => [f.stage, f.current, f.consistent ? "✓ 是" : "✗ 否", f.risk]),
  ];

  slide.addTable(tableData, {
    x: 0.6, y: 2.8, w: 12.1,
    colW: [2.5, 3.8, 1.2, 4.6],
    fontSize: 9,
    fontFace: "Arial",
    border: { type: "solid", color: C.lightGray, pt: 1 },
    rowH: 0.4,
    valign: "middle",
    color: C.textDark,
  });

  // Color-code the "确定性" column
  factors.forEach((f, i) => {
    slide.addShape("roundRect", {
      x: 6.9, y: 3.2 + i * 0.4, w: 1.2, h: 0.38,
      fill: { color: f.color, transparency: 80 },
      line: { color: f.color, width: 1 },
      rectRadius: 0.03,
    });
  });

  // Key insight
  addCard(slide, 0.6, 5.3, 12.1, 1.5, "FFF5F5", C.danger);
  slide.addText("关键发现", {
    x: 0.8, y: 5.38, w: 3, h: 0.3,
    fontSize: 12, fontFace: "Arial", bold: true, color: C.danger,
  });

  slide.addText([
    { text: "1. ", options: { bold: true, color: C.danger } },
    { text: "前 3 步（分类 → 范围 → 工具规划）已是确定性逻辑，同一输入必然走相同路径\n", options: { color: C.textDark } },
    { text: "2. ", options: { bold: true, color: C.danger } },
    { text: "summarize_answer 是唯一的不确定性来源：当 DeepSeek API Key 可用时，temperature=0.1 仍会产生不同文本\n", options: { color: C.textDark } },
    { text: "3. ", options: { bold: true, color: C.danger } },
    { text: "MCP 数据变化是业务正常的，但答案的", options: { color: C.textDark } },
    { text: "结构模板", options: { bold: true, color: C.danger } },
    { text: "和", options: { color: C.textDark } },
    { text: "推理逻辑", options: { bold: true, color: C.danger } },
    { text: "应保持一致", options: { color: C.textDark } },
  ], {
    x: 0.8, y: 5.7, w: 11.7, h: 1.0,
    fontSize: 10, fontFace: "Arial", valign: "top",
  });

  addFooter(slide, 9);
}

// ══════════════════════════════════════════════
// SLIDE 10: Consistency Solution Design
// ══════════════════════════════════════════════
{
  const slide = pptx.addSlide();
  addSlideBg(slide);
  addHeaderBar(slide, "方法论一致性：解决方案", "05 · 7-Layer Consistency Guarantee");

  const layers = [
    {
      num: "1", title: "确定性流水线优先",
      desc: "classify → scope → plan 三步保持纯关键词逻辑；模型只参与摘要增强，不参与路由决策",
      color: C.primary,
    },
    {
      num: "2", title: "Temperature = 0 + 固定 Seed",
      desc: "DeepSeek 调用 temperature 设为 0；如模型支持 seed 参数则传入固定值；失败时回退确定性摘要",
      color: C.accent,
    },
    {
      num: "3", title: "结构化答案模板",
      desc: "每个 Skill 绑定 answerTemplate，模型输出必须遵循模板结构（结论 → 数据 → 风险 → 数据范围），而非自由文本",
      color: C.accent2,
    },
    {
      num: "4", title: "查询结果缓存层",
      desc: "同一 (question_hash + project_ids + skill_id) 在 TTL 窗口内命中缓存，直接返回上次结论 + 数据时效声明",
      color: C.success,
    },
    {
      num: "5", title: "Prompt 版本管理",
      desc: "systemPrompt / answerTemplate 纳入版本控制，每次变更记录版本号；审计日志记录使用的 prompt 版本",
      color: C.danger,
    },
    {
      num: "6", title: "Golden Test 回归套件",
      desc: "维护 20+ 黄金测试用例（question → 期望技能 + 期望工具 + 答案结构断言），CI 每次变更自动运行",
      color: C.midGray,
    },
    {
      num: "7", title: "完整审计可回放",
      desc: "三级审计日志记录全链路（技能/工具/模型/prompt版本/参数），支持按 requestId 回放完整推理过程",
      color: C.primary,
    },
  ];

  layers.forEach((layer, i) => {
    const y = 1.3 + i * 0.82;

    addCard(slide, 0.6, y, 12.1, 0.72, C.lightBg, C.lightGray);

    // Number badge
    slide.addShape("ellipse", {
      x: 0.75, y: y + 0.11, w: 0.5, h: 0.5,
      fill: { color: layer.color },
    });
    slide.addText(layer.num, {
      x: 0.75, y: y + 0.11, w: 0.5, h: 0.5,
      fontSize: 14, fontFace: "Arial", bold: true, color: C.textLight, align: "center", valign: "middle",
    });

    slide.addText(layer.title, {
      x: 1.4, y: y + 0.05, w: 3.5, h: 0.35,
      fontSize: 12, fontFace: "Arial", bold: true, color: C.textDark, valign: "middle",
    });

    slide.addText(layer.desc, {
      x: 1.4, y: y + 0.38, w: 11.0, h: 0.3,
      fontSize: 9, fontFace: "Arial", color: C.midGray, valign: "middle",
    });
  });

  addFooter(slide, 10);
}

// ══════════════════════════════════════════════
// SLIDE 11: Consistency - Implementation Detail
// ══════════════════════════════════════════════
{
  const slide = pptx.addSlide();
  addSlideBg(slide);
  addHeaderBar(slide, "一致性保障：实现细节", "05 · Code-Level Changes Required");

  // Left: Current vs Target
  addCard(slide, 0.6, 1.3, 5.8, 5.6, C.lightBg, C.lightGray);
  slide.addText("代码变更清单", {
    x: 0.8, y: 1.4, w: 5, h: 0.35,
    fontSize: 13, fontFace: "Arial", bold: true, color: C.primary,
  });

  const changes = [
    {
      file: "agent_graph.py → _model_summary()",
      change: "temperature: 0.1 → 0.0\n新增 seed 参数（固定值）\n新增 prompt_version 记录",
      impact: "高",
    },
    {
      file: "skills.py → SkillDefinition",
      change: "新增 promptVersion 字段\nanswerTemplate 纳入版本管理",
      impact: "中",
    },
    {
      file: "agent-service/app/cache.py (新增)",
      change: "Redis 查询缓存\nKey: hash(question + project_ids + skill_id)\nTTL: 300s（可配置）",
      impact: "高",
    },
    {
      file: "backend AgentOrchestrator",
      change: "调用 agent-service 前先查缓存\n命中则直接回复 + 缓存标记",
      impact: "高",
    },
    {
      file: "tests/golden_cases.py (新增)",
      change: "20+ 黄金用例\n断言：技能分类 + 工具选择 + 答案结构\nCI 集成",
      impact: "中",
    },
    {
      file: "audit → agent_model_call_log",
      change: "新增 prompt_version 列\n记录 temperature / seed",
      impact: "低",
    },
  ];

  changes.forEach((c, i) => {
    const y = 1.85 + i * 0.82;
    slide.addShape("rect", {
      x: 0.8, y: y, w: 0.03, h: 0.72,
      fill: { color: c.impact === "高" ? C.danger : c.impact === "中" ? C.accent2 : C.midGray },
    });
    slide.addText(c.file, {
      x: 0.95, y: y, w: 5.2, h: 0.25,
      fontSize: 8, fontFace: "Courier New", bold: true, color: C.primary, valign: "middle",
    });
    slide.addText(c.change, {
      x: 0.95, y: y + 0.23, w: 5.2, h: 0.45,
      fontSize: 8, fontFace: "Arial", color: C.textDark, valign: "top",
    });
    slide.addText("[" + c.impact + "]", {
      x: 5.5, y: y, w: 0.7, h: 0.25,
      fontSize: 7, fontFace: "Arial", bold: true,
      color: c.impact === "高" ? C.danger : c.impact === "中" ? C.accent2 : C.midGray,
      align: "right", valign: "middle",
    });
  });

  // Right: Cache flow diagram
  addCard(slide, 6.7, 1.3, 6.0, 5.6, C.dark, null);
  slide.addText("查询缓存流程", {
    x: 6.9, y: 1.4, w: 5, h: 0.35,
    fontSize: 13, fontFace: "Arial", bold: true, color: C.accent,
  });

  const flowSteps = [
    { text: "用户提问", color: C.accent, y: 1.9 },
    { text: "计算 cache_key\n= hash(question + project_ids + skill_id)", color: C.primary, y: 2.5 },
    { text: "Redis GET cache_key", color: C.accent2, y: 3.3 },
    { text: "命中？", color: C.accent, y: 3.9, isDecision: true },
    { text: "✓ 命中 → 返回缓存结论\n+ 追加「数据截止时间」声明", color: C.success, y: 4.5 },
    { text: "✗ 未命中 → 正常编排\n→ 写入缓存 → 返回", color: C.danger, y: 5.4 },
  ];

  flowSteps.forEach((s, i) => {
    if (s.isDecision) {
      slide.addShape("diamond", {
        x: 8.5, y: s.y, w: 2.4, h: 0.55,
        fill: { color: s.color },
      });
      slide.addText(s.text, {
        x: 8.5, y: s.y, w: 2.4, h: 0.55,
        fontSize: 9, fontFace: "Arial", bold: true, color: C.textLight, align: "center", valign: "middle",
      });
    } else {
      slide.addShape("roundRect", {
        x: 7.5, y: s.y, w: 4.4, h: 0.55,
        fill: { color: s.color, transparency: 30 },
        line: { color: s.color, width: 1 },
        rectRadius: 0.06,
      });
      slide.addText(s.text, {
        x: 7.5, y: s.y, w: 4.4, h: 0.55,
        fontSize: 8, fontFace: "Arial", color: C.textLight, align: "center", valign: "middle",
      });
    }
    if (i < flowSteps.length - 1 && !flowSteps[i + 1].isDecision) {
      slide.addText("↓", {
        x: 9.4, y: s.y + 0.5, w: 1, h: 0.2,
        fontSize: 12, color: C.textMuted, align: "center",
      });
    }
  });

  // Yes/No branches
  slide.addText("→ 是", { x: 7.0, y: 4.55, w: 0.8, h: 0.3, fontSize: 8, color: C.success, bold: true });
  slide.addText("→ 否", { x: 11.2, y: 4.55, w: 0.8, h: 0.3, fontSize: 8, color: C.danger, bold: true });

  addFooter(slide, 11);
}

// ══════════════════════════════════════════════
// SLIDE 12: Technical Roadmap
// ══════════════════════════════════════════════
{
  const slide = pptx.addSlide();
  addSlideBg(slide);
  addHeaderBar(slide, "技术路线与演进规划", "06 · MVP → Engineering → Platform");

  // Timeline
  const phases = [
    {
      phase: "Phase 1",
      title: "MVP 稳定化",
      time: "当前 ~ +1月",
      color: C.danger,
      status: "进行中",
      items: [
        "修复 pom.xml 编译配置",
        "提交存量改动，分批 commit",
        "MCP 主密钥迁移环境变量",
        "默认凭据强改",
        "统一 DeepSeek 模型名",
        "补核心链路测试（编排/MCP适配）",
      ],
    },
    {
      phase: "Phase 2",
      title: "工程化与一致性",
      time: "+1 ~ +3月",
      color: C.accent2,
      status: "规划中",
      items: [
        "Temperature=0 + 固定 Seed",
        "结构化答案模板",
        "查询缓存层（Redis）",
        "Prompt 版本管理",
        "Golden Test 回归套件",
        "DebugController 权限收敛",
        "LLM-based 技能路由（可选）",
        "Docker 化部署 + CI/CD",
      ],
    },
    {
      phase: "Phase 3",
      title: "通用 Agent 平台",
      time: "+3 ~ +6月",
      color: C.accent,
      status: "远期",
      items: [
        "MCP 工具配置后台",
        "Agent Prompt 模板管理 UI",
        "多入口接入（Web/企微/钉钉）",
        "多步工具调用编排",
        "长期任务代理",
        "项目风险主动监控",
        "自动日报/周报/健康度分析",
        "多模型支持（不限于 DeepSeek）",
      ],
    },
  ];

  phases.forEach((p, i) => {
    const x = 0.6 + i * 4.2;

    // Phase header
    addCard(slide, x, 1.3, 3.9, 5.6, C.lightBg, p.color);

    slide.addShape("roundRect", {
      x, y: 1.3, w: 3.9, h: 0.85,
      fill: { color: p.color },
      rectRadius: 0.08,
    });

    slide.addText(p.phase, {
      x: x + 0.15, y: 1.33, w: 2.5, h: 0.3,
      fontSize: 11, fontFace: "Arial", color: C.textLight, valign: "middle",
    });
    slide.addText(p.title, {
      x: x + 0.15, y: 1.6, w: 2.8, h: 0.35,
      fontSize: 14, fontFace: "Arial", bold: true, color: C.textLight, valign: "middle",
    });

    // Status badge
    slide.addShape("roundRect", {
      x: x + 2.7, y: 1.38, w: 1.0, h: 0.25,
      fill: { color: C.textLight, transparency: 75 },
      rectRadius: 0.03,
    });
    slide.addText(p.status, {
      x: x + 2.7, y: 1.38, w: 1.0, h: 0.25,
      fontSize: 8, fontFace: "Arial", bold: true, color: C.textLight, align: "center", valign: "middle",
    });

    // Time
    slide.addText("🕐 " + p.time, {
      x: x + 0.15, y: 2.25, w: 3.5, h: 0.3,
      fontSize: 10, fontFace: "Arial", color: C.midGray, italic: true,
    });

    // Items
    p.items.forEach((item, j) => {
      slide.addShape("ellipse", {
        x: x + 0.2, y: 2.75 + j * 0.48, w: 0.1, h: 0.1,
        fill: { color: p.color },
      });
      slide.addText(item, {
        x: x + 0.4, y: 2.7 + j * 0.48, w: 3.3, h: 0.4,
        fontSize: 9, fontFace: "Arial", color: C.textDark, valign: "middle",
      });
    });

    // Arrow between phases
    if (i < phases.length - 1) {
      slide.addText("→", {
        x: x + 3.85, y: 3.5, w: 0.4, h: 0.5,
        fontSize: 22, color: C.midGray, align: "center", valign: "middle", bold: true,
      });
    }
  });

  addFooter(slide, 12);
}

// ══════════════════════════════════════════════
// SLIDE 13: Tech Stack Detail
// ══════════════════════════════════════════════
{
  const slide = pptx.addSlide();
  addSlideBg(slide);
  addHeaderBar(slide, "技术栈全景", "06 · Technology Stack Overview");

  const stackAreas = [
    {
      title: "后端 (backend)",
      color: C.primary,
      x: 0.6, y: 1.3, w: 3.85,
      items: [
        "Java 17",
        "Spring Boot 3.3.7",
        "Spring Security",
        "Spring AMQP (RabbitMQ)",
        "Spring JDBC",
        "Flyway 6 (V1-V5)",
        "MySQL 8",
        "Lark OAPI SDK 2.4.0",
        "Jackson / RestClient",
        "Bean Validation",
      ],
    },
    {
      title: "智能层 (agent-service)",
      color: C.accent,
      x: 4.7, y: 1.3, w: 3.85,
      items: [
        "Python 3.10+",
        "FastAPI 0.115",
        "LangChain 0.3",
        "LangGraph 0.2",
        "Pydantic",
        "LangChain-OpenAI (DeepSeek)",
        "Uvicorn ASGI",
        "5-Step StateGraph",
        "Keyword-based Classifier",
        "Deterministic Fallback",
      ],
    },
    {
      title: "前端 (admin-web)",
      color: C.accent2,
      x: 8.8, y: 1.3, w: 3.85,
      items: [
        "Vue 3 (Composition API)",
        "Ant Design Vue 4",
        "Vite 6",
        "TypeScript",
        "Pinia (状态管理)",
        "Vue Router",
        "Axios",
        "10 Views / 3 Components",
        "CrudPage 通用组件",
        "ResultViewer JSON 展示",
      ],
    },
  ];

  stackAreas.forEach(area => {
    addCard(slide, area.x, area.y, area.w, 3.5, C.lightBg, area.color);

    slide.addShape("roundRect", {
      x: area.x, y: area.y, w: area.w, h: 0.5,
      fill: { color: area.color },
      rectRadius: 0.06,
    });
    slide.addText(area.title, {
      x: area.x, y: area.y, w: area.w, h: 0.5,
      fontSize: 12, fontFace: "Arial", bold: true, color: C.textLight, align: "center", valign: "middle",
    });

    area.items.forEach((item, i) => {
      slide.addText("▸ " + item, {
        x: area.x + 0.2, y: area.y + 0.6 + i * 0.28, w: area.w - 0.4, h: 0.25,
        fontSize: 9, fontFace: "Arial", color: C.textDark, valign: "middle",
      });
    });
  });

  // Infrastructure row
  addCard(slide, 0.6, 5.1, 12.1, 1.9, C.dark, null);
  slide.addText("基础设施与中间件", {
    x: 0.8, y: 5.2, w: 4, h: 0.35,
    fontSize: 12, fontFace: "Arial", bold: true, color: C.accent,
  });

  const infra = [
    { label: "MySQL 8", desc: "9 张表 + Flyway 迁移" },
    { label: "RabbitMQ", desc: "异步任务队列" },
    { label: "DeepSeek API", desc: "底层大模型 (plan + summarize)" },
    { label: "Primelayer MCP", desc: "Streamable HTTP 协议适配" },
    { label: "Feishu OpenAPI", desc: "事件回调 + 消息回复" },
    { label: "AES-GCM", desc: "Token 密文存储" },
  ];

  infra.forEach((item, i) => {
    const col = i % 3;
    const row = Math.floor(i / 3);
    const ix = 0.8 + col * 4.0;
    const iy = 5.6 + row * 0.65;

    slide.addShape("roundRect", {
      x: ix, y: iy, w: 3.7, h: 0.55,
      fill: { color: C.primary, transparency: 80 },
      line: { color: C.accent, width: 1 },
      rectRadius: 0.05,
    });

    slide.addText(item.label, {
      x: ix + 0.1, y: iy, w: 1.5, h: 0.55,
      fontSize: 10, fontFace: "Arial", bold: true, color: C.accent, valign: "middle",
    });
    slide.addText(item.desc, {
      x: ix + 1.5, y: iy, w: 2.1, h: 0.55,
      fontSize: 8, fontFace: "Arial", color: C.textLight, valign: "middle",
    });
  });

  addFooter(slide, 13);
}

// ══════════════════════════════════════════════
// SLIDE 14: Summary & Next Steps
// ══════════════════════════════════════════════
{
  const slide = pptx.addSlide();
  addSlideBg(slide, C.dark);

  // Decorative
  slide.addShape("rect", { x: 0, y: 0, w: 13.33, h: 0.12, fill: { color: C.accent } });
  slide.addShape("rect", { x: 0, y: 7.38, w: 13.33, h: 0.12, fill: { color: C.accent } });

  slide.addShape("ellipse", { x: -1, y: -1, w: 3, h: 3, fill: { color: C.primary, transparency: 80 } });
  slide.addShape("ellipse", { x: 11, y: 5, w: 3, h: 3, fill: { color: C.accent, transparency: 80 } });

  slide.addText("总结与下一步", {
    x: 0.6, y: 0.6, w: 8, h: 0.6,
    fontSize: 28, fontFace: "Arial", bold: true, color: C.textLight,
  });

  slide.addShape("rect", { x: 0.6, y: 1.15, w: 3, h: 0.04, fill: { color: C.accent2 } });

  // Summary points
  const summaries = [
    {
      title: "项目现状",
      text: "架构清晰、职责分明的 MVP 已基本成型。Java 安全边界 + Python 智能层 + Vue 管理后台，三级审计齐全，异步化设计周到。",
      color: C.accent,
    },
    {
      title: "优化方向",
      text: "P0 修编译配置 + 迁密钥 → P1 提交存量 + 强改凭据 + 补测试 → P2 升级智能路由 + 收敛调试接口。",
      color: C.accent2,
    },
    {
      title: "一致性保障",
      text: "7 层方案：确定性流水线 + temperature=0 + 模板化输出 + 查询缓存 + Prompt 版本管理 + Golden Test + 审计回放。",
      color: C.success,
    },
    {
      title: "技术路线",
      text: "Phase 1 MVP 稳定化 → Phase 2 工程化与一致性 → Phase 3 通用 Agent 平台（多入口、多步工具、主动监控）。",
      color: C.primary,
    },
  ];

  summaries.forEach((s, i) => {
    const y = 1.5 + i * 1.35;

    slide.addShape("roundRect", {
      x: 0.6, y, w: 12.1, h: 1.2,
      fill: { color: C.dark, transparency: 50 },
      line: { color: s.color, width: 1.5 },
      rectRadius: 0.08,
    });

    slide.addShape("rect", {
      x: 0.6, y, w: 0.08, h: 1.2,
      fill: { color: s.color },
    });

    slide.addText(s.title, {
      x: 0.85, y: y + 0.1, w: 3, h: 0.35,
      fontSize: 14, fontFace: "Arial", bold: true, color: s.color, valign: "middle",
    });

    slide.addText(s.text, {
      x: 0.85, y: y + 0.45, w: 11.6, h: 0.65,
      fontSize: 11, fontFace: "Arial", color: C.textLight, valign: "middle",
    });
  });

  // Immediate actions
  slide.addShape("roundRect", {
    x: 0.6, y: 6.5, w: 12.1, h: 0.65,
    fill: { color: C.accent2, transparency: 70 },
    line: { color: C.accent2, width: 1 },
    rectRadius: 0.06,
  });

  slide.addText([
    { text: "立即行动：", options: { bold: true, color: C.accent2, fontSize: 11 } },
    { text: "  ① 修 pom.xml 编译配置   ② 提交存量改动   ③ 迁移 MCP 主密钥   ④ 强改默认凭据   ⑤ 补核心链路测试", options: { color: C.textLight, fontSize: 10 } },
  ], {
    x: 0.8, y: 6.55, w: 11.7, h: 0.55,
    fontFace: "Arial", valign: "middle",
  });

  addFooter(slide, 14);
}

// ── Save ──
const outputPath = path.join(__dirname, "Lark_Connect_项目分析与技术路线.pptx");
pptx.writeFile({ fileName: outputPath }).then(() => {
  console.log("PPT generated: " + outputPath);
}).catch(err => {
  console.error("Error:", err);
  process.exit(1);
});
