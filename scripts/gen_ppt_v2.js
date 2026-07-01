const PptxGenJS = require("pptxgenjs");
const path = require("path");

const pptx = new PptxGenJS();
pptx.layout = "LAYOUT_WIDE";
pptx.author = "Lark Connect Team";

const C = {
  primary: "0F4C81",
  accent: "00A6A6",
  accent2: "F5A623",
  danger: "E84855",
  success: "2D9D5F",
  dark: "1A1A2E",
  bg: "FFFFFF",
  lightBg: "F0F4F8",
  lightGray: "E8EDF2",
  midGray: "6B7B8D",
  textDark: "2C3E50",
  textLight: "FFFFFF",
  textMuted: "8B9DAF",
};

function addSlideBg(slide, color = C.bg) {
  slide.background = { color };
}

function addHeaderBar(slide, title, subtitle) {
  slide.addShape("rect", { x: 0, y: 0, w: 13.33, h: 0.08, fill: { color: C.primary } });
  slide.addShape("rect", { x: 0, y: 0.08, w: 13.33, h: 0.03, fill: { color: C.accent } });
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
  slide.addShape("rect", { x: 0, y: 0, w: 13.33, h: 0.12, fill: { color: C.primary } });
  slide.addShape("rect", { x: 0, y: 0.12, w: 13.33, h: 0.06, fill: { color: C.accent } });
  slide.addShape("rect", { x: 0, y: 7.32, w: 13.33, h: 0.06, fill: { color: C.accent } });
  slide.addShape("rect", { x: 0, y: 7.38, w: 13.33, h: 0.12, fill: { color: C.primary } });
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
  slide.addText("我做了什么  |  以后我该怎么做", {
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
// SLIDE 2: What I Did - Project Overview
// ══════════════════════════════════════════════
{
  const slide = pptx.addSlide();
  addSlideBg(slide);
  addHeaderBar(slide, "我做了什么", "项目定位与核心价值");

  addCard(slide, 0.6, 1.3, 12.1, 0.8, C.dark, null);
  slide.addText([
    { text: "项目定位：", options: { bold: true, color: C.accent2, fontSize: 12 } },
    { text: "  Lark Connect Agent Gateway — 打通 Primelayer（地产项目管理系统）与飞书，让用户用自然语言查询项目数据、待办和风险。", options: { color: C.textLight, fontSize: 12 } },
  ], { x: 0.8, y: 1.35, w: 11.7, h: 0.7, fontFace: "Arial", valign: "middle" });

  const facts = [
    "底层大模型：DeepSeek，支持 plan + summarize 双阶段调用",
    "身份链路：feishu_open_id → primelayer_user_id → project_id → mcp_token",
    "安全设计：MCP Token AES-GCM 密文存储，永不传入智能层",
    "异步架构：RabbitMQ 任务队列 + 飞书异步回复，避免超时",
    "审计体系：三级审计（任务 / 工具调用 / 模型调用）全覆盖",
    "多场景支持：私聊单项目 / 私聊跨项目 / 群聊项目上下文",
  ];

  facts.forEach((fact, i) => {
    const col = i < 3 ? 0 : 1;
    const row = i % 3;
    const fx = 0.8 + col * 6.0;
    const fy = 2.3 + row * 0.55;
    slide.addShape("ellipse", { x: fx, y: fy + 0.02, w: 0.12, h: 0.12, fill: { color: C.accent } });
    slide.addText(fact, { x: fx + 0.2, y: fy, w: 5.5, h: 0.35, fontSize: 11, fontFace: "Arial", color: C.textDark, valign: "middle" });
  });

  addCard(slide, 0.6, 4.1, 5.8, 2.5, C.lightBg, C.primary);
  slide.addText("核心价值", { x: 0.8, y: 4.2, w: 5, h: 0.4, fontSize: 14, fontFace: "Arial", bold: true, color: C.primary });
  const values = [
    { icon: "[1]", text: "自然语言即查询入口，降低 Primelayer 使用门槛" },
    { icon: "[2]", text: "Java 后端是唯一安全边界，Token 不出后端" },
    { icon: "[3]", text: "三级审计齐全，每次查询可追溯可回放" },
  ];
  values.forEach((v, i) => {
    slide.addText(v.icon, { x: 0.8, y: 4.7 + i * 0.6, w: 0.4, h: 0.4, fontSize: 14, bold: true, color: C.accent, align: "center", valign: "middle" });
    slide.addText(v.text, { x: 1.3, y: 4.7 + i * 0.6, w: 4.8, h: 0.4, fontSize: 11, fontFace: "Arial", color: C.textDark, valign: "middle" });
  });

  addCard(slide, 6.7, 4.1, 6.0, 2.5, C.lightBg, C.accent);
  slide.addText("一期交付物", { x: 6.9, y: 4.2, w: 5, h: 0.4, fontSize: 14, fontFace: "Arial", bold: true, color: C.accent });
  const deliverables = [
    "backend: 飞书事件接收 + 权限 + MCP 适配 + 审计 + 管理后台 API",
    "agent-service: 5 步 LangGraph 状态机 + 5 个技能定义 + 确定性摘要",
    "admin-web: 10 视图 + 3 通用组件 (CrudPage / ReadonlyTable / ResultViewer)",
  ];
  deliverables.forEach((d, i) => {
    slide.addShape("ellipse", { x: 6.9, y: 4.72 + i * 0.6, w: 0.12, h: 0.12, fill: { color: C.accent } });
    slide.addText(d, { x: 7.1, y: 4.7 + i * 0.6, w: 5.3, h: 0.4, fontSize: 11, fontFace: "Arial", color: C.textDark, valign: "middle" });
  });

  addFooter(slide, 2);
}

// ══════════════════════════════════════════════
// SLIDE 3: What I Did - Architecture (like the screenshot)
// ══════════════════════════════════════════════
{
  const slide = pptx.addSlide();
  addSlideBg(slide);
  addHeaderBar(slide, "我做了什么", "系统架构 — 三模块分层");

  // Entry layer
  slide.addShape("roundRect", { x: 0.6, y: 1.4, w: 2.2, h: 0.7, fill: { color: "E6F1FB" }, line: { color: "378ADD", width: 1 }, rectRadius: 0.06 });
  slide.addText("飞书用户", { x: 0.6, y: 1.45, w: 2.2, h: 0.3, fontSize: 11, fontFace: "Arial", bold: true, color: "0C447C", align: "center", valign: "middle" });
  slide.addText("私聊 / 群聊", { x: 0.6, y: 1.7, w: 2.2, h: 0.2, fontSize: 9, fontFace: "Arial", color: "185FA5", align: "center", valign: "middle" });

  slide.addShape("roundRect", { x: 3.2, y: 1.4, w: 2.2, h: 0.7, fill: { color: "E1F5EE" }, line: { color: "1D9E75", width: 1 }, rectRadius: 0.06 });
  slide.addText("admin-web", { x: 3.2, y: 1.45, w: 2.2, h: 0.3, fontSize: 11, fontFace: "Arial", bold: true, color: "085041", align: "center", valign: "middle" });
  slide.addText("Vue3 管理后台", { x: 3.2, y: 1.7, w: 2.2, h: 0.2, fontSize: 9, fontFace: "Arial", color: "0F6E56", align: "center", valign: "middle" });

  // Arrows down
  slide.addText("▼", { x: 1.5, y: 2.05, w: 0.4, h: 0.2, fontSize: 10, color: C.midGray, align: "center" });
  slide.addText("▼", { x: 4.1, y: 2.05, w: 0.4, h: 0.2, fontSize: 10, color: C.midGray, align: "center" });

  // Backend big box
  slide.addShape("roundRect", { x: 0.6, y: 2.4, w: 8.0, h: 3.2, fill: { color: "EEEDFE" }, line: { color: "7F77DD", width: 1.5 }, rectRadius: 0.12 });
  slide.addText("Backend · Java 17 + Spring Boot 3.3 （安全边界）", { x: 0.8, y: 2.55, w: 7.6, h: 0.3, fontSize: 12, fontFace: "Arial", bold: true, color: "3C3489", valign: "middle" });

  // Backend modules
  const backendModules = [
    { x: 0.8, y: 2.95, label: "飞书事件接收", desc: "Controller / WS" },
    { x: 3.0, y: 2.95, label: "管理后台 API", desc: "Admin / Debug" },
    { x: 5.2, y: 2.95, label: "Token 加解密", desc: "AES-GCM" },
    { x: 0.8, y: 3.7, label: "Agent 编排", desc: "RabbitMQ 异步" },
    { x: 3.0, y: 3.7, label: "MCP 适配器", desc: "tools/list · tools/call" },
    { x: 5.2, y: 3.7, label: "审计 / 回复飞书", desc: "Audit + FeishuClient" },
  ];
  backendModules.forEach(m => {
    slide.addShape("roundRect", { x: m.x, y: m.y, w: 1.8, h: 0.6, fill: { color: "FFFFFF" }, line: { color: "378ADD", width: 0.8 }, rectRadius: 0.05 });
    slide.addText(m.label, { x: m.x, y: m.y + 0.02, w: 1.8, h: 0.3, fontSize: 9, fontFace: "Arial", bold: true, color: "0C447C", align: "center", valign: "middle" });
    slide.addText(m.desc, { x: m.x, y: m.y + 0.28, w: 1.8, h: 0.25, fontSize: 7, fontFace: "Arial", color: "185FA5", align: "center", valign: "middle" });
  });

  // Agent-Service box
  slide.addShape("roundRect", { x: 9.0, y: 2.4, w: 3.7, h: 3.2, fill: { color: "E1F5EE" }, line: { color: "1D9E75", width: 1.5 }, rectRadius: 0.12 });
  slide.addText("Agent-Service", { x: 9.2, y: 2.55, w: 3.3, h: 0.3, fontSize: 12, fontFace: "Arial", bold: true, color: "085041", valign: "middle" });
  slide.addText("Python · FastAPI + LangGraph", { x: 9.2, y: 2.85, w: 3.3, h: 0.2, fontSize: 9, fontFace: "Arial", color: "0F6E56", valign: "middle" });

  const agentSteps = ["1. 技能路由", "2. 项目范围判定", "3. 工具调用规划", "4. 答案摘要", "5. 质量校验"];
  agentSteps.forEach((s, i) => {
    slide.addText(s, { x: 9.2, y: 3.2 + i * 0.45, w: 3.3, h: 0.35, fontSize: 10, fontFace: "Arial", color: "085041", valign: "middle" });
  });

  // Bidirectional arrow
  slide.addText("→", { x: 8.6, y: 3.2, w: 0.4, h: 0.4, fontSize: 16, color: C.midGray, align: "center", valign: "middle" });
  slide.addText("←", { x: 8.6, y: 3.9, w: 0.4, h: 0.4, fontSize: 16, color: C.midGray, align: "center", valign: "middle" });
  slide.addText("规划/摘要", { x: 8.5, y: 3.55, w: 0.6, h: 0.2, fontSize: 7, fontFace: "Arial", color: C.midGray, align: "center" });

  // Data sources
  const dataSources = [
    { x: 0.6, y: 5.9, label: "MySQL 8", desc: "Flyway 迁移", color: "FAEEDA", stroke: "BA7517", text: "412402" },
    { x: 3.2, y: 5.9, label: "RabbitMQ", desc: "异步任务队列", color: "FAEEDA", stroke: "BA7517", text: "412402" },
    { x: 5.8, y: 5.9, label: "Primelayer MCP", desc: "外部数据源", color: "FAECE7", stroke: "D85A30", text: "4A1B0C" },
  ];
  dataSources.forEach(ds => {
    slide.addShape("roundRect", { x: ds.x, y: ds.y, w: 2.2, h: 0.7, fill: { color: ds.color }, line: { color: ds.stroke, width: 1 }, rectRadius: 0.06 });
    slide.addText(ds.label, { x: ds.x, y: ds.y + 0.05, w: 2.2, h: 0.3, fontSize: 11, fontFace: "Arial", bold: true, color: ds.text, align: "center", valign: "middle" });
    slide.addText(ds.desc, { x: ds.x, y: ds.y + 0.3, w: 2.2, h: 0.2, fontSize: 9, fontFace: "Arial", color: ds.stroke, align: "center", valign: "middle" });
  });

  // Arrows from backend to data sources
  slide.addText("▼", { x: 1.5, y: 5.55, w: 0.4, h: 0.2, fontSize: 10, color: C.midGray, align: "center" });
  slide.addText("▼", { x: 4.1, y: 5.55, w: 0.4, h: 0.2, fontSize: 10, color: C.midGray, align: "center" });
  slide.addText("▼", { x: 6.7, y: 5.55, w: 0.4, h: 0.2, fontSize: 10, color: C.midGray, align: "center" });

  // Legend
  slide.addText("接入层", { x: 0.8, y: 6.75, w: 0.8, h: 0.2, fontSize: 9, fontFace: "Arial", color: C.midGray, valign: "middle" });
  slide.addShape("rect", { x: 0.6, y: 6.78, w: 0.14, h: 0.14, fill: { color: "E6F1FB" }, line: { color: "378ADD", width: 0.5 } });
  slide.addText("安全边界", { x: 2.0, y: 6.75, w: 0.8, h: 0.2, fontSize: 9, fontFace: "Arial", color: C.midGray, valign: "middle" });
  slide.addShape("rect", { x: 1.8, y: 6.78, w: 0.14, h: 0.14, fill: { color: "EEEDFE" }, line: { color: "7F77DD", width: 0.5 } });
  slide.addText("智能层", { x: 3.2, y: 6.75, w: 0.8, h: 0.2, fontSize: 9, fontFace: "Arial", color: C.midGray, valign: "middle" });
  slide.addShape("rect", { x: 3.0, y: 6.78, w: 0.14, h: 0.14, fill: { color: "E1F5EE" }, line: { color: "1D9E75", width: 0.5 } });
  slide.addText("存储层", { x: 4.4, y: 6.75, w: 0.8, h: 0.2, fontSize: 9, fontFace: "Arial", color: C.midGray, valign: "middle" });
  slide.addShape("rect", { x: 4.2, y: 6.78, w: 0.14, h: 0.14, fill: { color: "FAEEDA" }, line: { color: "BA7517", width: 0.5 } });
  slide.addText("外部数据源", { x: 5.6, y: 6.75, w: 1.0, h: 0.2, fontSize: 9, fontFace: "Arial", color: C.midGray, valign: "middle" });
  slide.addShape("rect", { x: 5.4, y: 6.78, w: 0.14, h: 0.14, fill: { color: "FAECE7" }, line: { color: "D85A30", width: 0.5 } });

  addFooter(slide, 3);
}

// ══════════════════════════════════════════════
// SLIDE 4: What I Did - Core Data Flow
// ══════════════════════════════════════════════
{
  const slide = pptx.addSlide();
  addSlideBg(slide);
  addHeaderBar(slide, "我做了什么", "核心数据流 — 端到端消息处理");

  const flowTop = [
    { label: "飞书消息", desc: "FeishuEventController", color: "378ADD", x: 0.6 },
    { label: "RabbitMQ", desc: "异步入队", color: "EF9F27", x: 2.3 },
    { label: "AgentWorker", desc: "消费消息", color: "0F4C81", x: 4.0 },
    { label: "TokenResolver", desc: "解密 MCP Token", color: "E84855", x: 5.7 },
    { label: "AgentOrchestrator", desc: "编排核心流程", color: "0F4C81", x: 7.4 },
    { label: "agent-service", desc: "技能路由 + 工具规划", color: "00A6A6", x: 9.1 },
  ];

  flowTop.forEach((step, i) => {
    slide.addShape("roundRect", { x: step.x, y: 1.5, w: 1.5, h: 0.85, fill: { color: step.color }, rectRadius: 0.06 });
    slide.addText(step.label, { x: step.x, y: 1.55, w: 1.5, h: 0.35, fontSize: 9, fontFace: "Arial", bold: true, color: C.textLight, align: "center", valign: "middle" });
    slide.addText(step.desc, { x: step.x, y: 1.88, w: 1.5, h: 0.35, fontSize: 7, fontFace: "Arial", color: C.textLight, align: "center", valign: "middle" });
    if (i < flowTop.length - 1) {
      slide.addText("→", { x: step.x + 1.45, y: 1.65, w: 0.3, h: 0.5, fontSize: 16, color: C.midGray, align: "center", valign: "middle", bold: true });
    }
  });

  slide.addText("← toolCalls", { x: 7.4, y: 2.5, w: 3.2, h: 0.25, fontSize: 8, fontFace: "Arial", color: C.midGray, align: "center", italic: true });

  const flowBottom = [
    { label: "McpAdapter", desc: "携 Token 调 Primelayer MCP", color: "E84855", x: 3.0 },
    { label: "工具结果回传", desc: "agent-service Summarization", color: "00A6A6", x: 5.0 },
    { label: "FeishuClient", desc: "异步回复飞书", color: "EF9F27", x: 7.0 },
    { label: "AuditService", desc: "写三级审计日志", color: "0F4C81", x: 9.0 },
  ];

  flowBottom.forEach((step, i) => {
    slide.addShape("roundRect", { x: step.x, y: 3.0, w: 1.7, h: 0.85, fill: { color: step.color, transparency: 20 }, line: { color: step.color, width: 1.5 }, rectRadius: 0.06 });
    slide.addText(step.label, { x: step.x, y: 3.05, w: 1.7, h: 0.35, fontSize: 9, fontFace: "Arial", bold: true, color: step.color, align: "center", valign: "middle" });
    slide.addText(step.desc, { x: step.x, y: 3.38, w: 1.7, h: 0.35, fontSize: 7, fontFace: "Arial", color: C.midGray, align: "center", valign: "middle" });
    if (i < flowBottom.length - 1) {
      slide.addText("→", { x: step.x + 1.65, y: 3.15, w: 0.3, h: 0.5, fontSize: 16, color: C.midGray, align: "center", valign: "middle", bold: true });
    }
  });

  addCard(slide, 0.6, 4.3, 12.1, 2.5, C.lightBg, C.lightGray);
  slide.addText("关键设计特征", { x: 0.8, y: 4.4, w: 5, h: 0.35, fontSize: 13, fontFace: "Arial", bold: true, color: C.primary });
  const features = [
    { title: "异步解耦", desc: "飞书消息 → RabbitMQ → Worker，消息接收与处理解耦" },
    { title: "安全边界", desc: "MCP Token 仅在 Java 后端解密，agent-service 只收脱敏上下文" },
    { title: "多轮编排", desc: "MAX_AGENT_SERVICE_ROUNDS = 4，支持循环规划" },
    { title: "降级策略", desc: "agent-service 不可用时回退到 Legacy DeepSeek 链路" },
  ];
  features.forEach((f, i) => {
    const col = i % 2;
    const row = Math.floor(i / 2);
    const fx = 0.8 + col * 6.0;
    const fy = 4.85 + row * 0.55;
    slide.addText("▸", { x: fx, y: fy, w: 0.2, h: 0.35, fontSize: 11, color: C.accent, valign: "middle" });
    slide.addText(f.title + "：", { x: fx + 0.2, y: fy, w: 1.2, h: 0.35, fontSize: 10, fontFace: "Arial", bold: true, color: C.primary, valign: "middle" });
    slide.addText(f.desc, { x: fx + 1.4, y: fy, w: 4.2, h: 0.35, fontSize: 9, fontFace: "Arial", color: C.textDark, valign: "middle" });
  });

  addFooter(slide, 4);
}

// ══════════════════════════════════════════════
// SLIDE 5: What I Did - Agent State Machine
// ══════════════════════════════════════════════
{
  const slide = pptx.addSlide();
  addSlideBg(slide);
  addHeaderBar(slide, "我做了什么", "Agent 状态机 — 5 步 LangGraph 流水线 + 5 技能");

  const states = [
    { name: "classify_skill", desc: "关键词匹配\n技能分类", color: "00A6A6" },
    { name: "select_project_scope", desc: "确定查询\n项目范围", color: "F5A623" },
    { name: "plan_tool_calls", desc: "选择只读工具\n构建调用参数", color: "0F4C81" },
    { name: "summarize_answer", desc: "模型摘要\n或确定性摘要", color: "00A6A6" },
    { name: "quality_check", desc: "追加数据范围\n声明", color: "2D9D5F" },
  ];

  states.forEach((state, i) => {
    const sx = 0.6 + i * 2.55;
    slide.addShape("roundRect", { x: sx, y: 1.3, w: 2.2, h: 1.1, fill: { color: state.color }, rectRadius: 0.1 });
    slide.addText(state.name, { x: sx, y: 1.35, w: 2.2, h: 0.35, fontSize: 10, fontFace: "Courier New", bold: true, color: C.textLight, align: "center", valign: "middle" });
    slide.addText(state.desc, { x: sx, y: 1.68, w: 2.2, h: 0.65, fontSize: 8, fontFace: "Arial", color: C.textLight, align: "center", valign: "middle" });
    if (i < states.length - 1) {
      slide.addText("→", { x: sx + 2.15, y: 1.5, w: 0.5, h: 0.7, fontSize: 22, color: C.midGray, align: "center", valign: "middle", bold: true });
    }
  });

  slide.addText("技能体系（5 个 SkillDefinition）", { x: 0.6, y: 2.7, w: 8, h: 0.35, fontSize: 13, fontFace: "Arial", bold: true, color: C.primary });

  const skills = [
    { id: "project_report", name: "项目报告与施工情况", triggers: "施工日报、质量安全、安全隐患、进度" },
    { id: "project_status_qa", name: "项目状态问答", triggers: "项目怎么样、当前进度、健康度" },
    { id: "task_risk_qa", name: "任务风险问答", triggers: "逾期、风险、待办、负责人、阻塞" },
    { id: "weekly_report", name: "日报周报生成", triggers: "周报、日报、总结、本周、下周" },
    { id: "general_mcp_qa", name: "通用 MCP 问答（兜底）", triggers: "查询、看看、帮我分析" },
  ];

  const tableRows = [["技能 ID", "技能名称", "触发关键词"]];
  skills.forEach(s => tableRows.push([s.id, s.name, s.triggers]));
  slide.addTable(tableRows, {
    x: 0.6, y: 3.1, w: 12.1,
    colW: [2.5, 3.0, 6.6],
    fontSize: 9, fontFace: "Arial",
    border: { type: "solid", color: C.lightGray, pt: 1 },
    rowH: [0.35, 0.45, 0.45, 0.45, 0.45, 0.45],
    valign: "middle", color: C.textDark, align: "left",
  });

  addCard(slide, 0.6, 5.5, 12.1, 1.2, C.dark, null);
  slide.addText([
    { text: "关键约束：", options: { bold: true, color: "F5A623", fontSize: 10 } },
    { text: "  只选只读工具（get_/list_/query_/search_ 前缀）·  关键词匹配分类（确定性逻辑）·  摘要可选模型（temperature=0.1），无 API Key 时走确定性摘要", options: { color: C.textLight, fontSize: 10 } },
  ], { x: 0.8, y: 5.55, w: 11.7, h: 1.1, fontFace: "Arial", valign: "middle" });

  addFooter(slide, 5);
}

// ══════════════════════════════════════════════
// SLIDE 6: What I Did - Database
// ══════════════════════════════════════════════
{
  const slide = pptx.addSlide();
  addSlideBg(slide);
  addHeaderBar(slide, "我做了什么", "数据库设计 — MySQL 8 + Flyway V1-V5");

  const tables = [
    { name: "admin_user", desc: "管理员账户", group: "管理", color: "0F4C81" },
    { name: "user_binding", desc: "飞书↔Primelayer\n用户绑定", group: "身份", color: "00A6A6" },
    { name: "project_mcp_token", desc: "项目级 MCP Token\n（密文存储）", group: "安全", color: "E84855" },
    { name: "feishu_chat_project_binding", desc: "飞书群↔项目\n绑定", group: "身份", color: "00A6A6" },
    { name: "agent_task", desc: "异步任务", group: "任务", color: "F5A623" },
    { name: "agent_audit_log", desc: "任务级审计日志", group: "审计", color: "2D9D5F" },
    { name: "agent_tool_call_log", desc: "工具调用审计\n日志", group: "审计", color: "2D9D5F" },
    { name: "agent_model_call_log", desc: "模型调用审计\n日志", group: "审计", color: "2D9D5F" },
    { name: "system_config", desc: "系统配置\n（含 AES 主密钥）", group: "配置", color: "6B7B8D" },
  ];

  const groups = {};
  tables.forEach(t => { if (!groups[t.group]) groups[t.group] = []; groups[t.group].push(t); });

  let yPos = 1.4;
  Object.entries(groups).forEach(([group, groupTables]) => {
    slide.addText(group, { x: 0.6, y: yPos, w: 1.5, h: 0.3, fontSize: 11, fontFace: "Arial", bold: true, color: C.midGray });
    groupTables.forEach((t, i) => {
      const xPos = 2.2 + i * 2.85;
      slide.addShape("roundRect", { x: xPos, y: yPos - 0.05, w: 2.6, h: 1.0, fill: { color: C.lightBg }, line: { color: t.color, width: 1.5 }, rectRadius: 0.06 });
      slide.addShape("rect", { x: xPos, y: yPos - 0.05, w: 2.6, h: 0.3, fill: { color: t.color } });
      slide.addText(t.name, { x: xPos, y: yPos - 0.05, w: 2.6, h: 0.3, fontSize: 9, fontFace: "Courier New", bold: true, color: C.textLight, align: "center", valign: "middle" });
      slide.addText(t.desc, { x: xPos + 0.1, y: yPos + 0.28, w: 2.4, h: 0.65, fontSize: 9, fontFace: "Arial", color: C.textDark, align: "center", valign: "middle" });
    });
    yPos += 1.25;
  });

  addCard(slide, 0.6, 5.7, 12.1, 1.1, C.dark, null);
  slide.addText("Flyway 迁移历史", { x: 0.8, y: 5.78, w: 3, h: 0.3, fontSize: 11, fontFace: "Arial", bold: true, color: C.accent });
  slide.addText([
    { text: "V1 ", options: { bold: true, color: "F5A623" } }, { text: "初始化 9 张表    ", options: { color: C.textLight } },
    { text: "V2 ", options: { bold: true, color: "F5A623" } }, { text: "user_binding 增加 person_name    ", options: { color: C.textLight } },
    { text: "V3 ", options: { bold: true, color: "F5A623" } }, { text: "project_mcp_token 增加验证状态    ", options: { color: C.textLight } },
    { text: "V4 ", options: { bold: true, color: "F5A623" } }, { text: "增加 owner 字段    ", options: { color: C.textLight } },
    { text: "V5 ", options: { bold: true, color: "F5A623" } }, { text: "Token 去重", options: { color: C.textLight } },
  ], { x: 0.8, y: 6.1, w: 11.7, h: 0.6, fontSize: 10, fontFace: "Arial", valign: "middle" });

  addFooter(slide, 6);
}

// ══════════════════════════════════════════════
// SLIDE 7: What to Do Next - Issues
// ══════════════════════════════════════════════
{
  const slide = pptx.addSlide();
  addSlideBg(slide);
  addHeaderBar(slide, "以后我该怎么做", "当前问题与优化建议 — P0 / P1 / P2");

  addCard(slide, 0.6, 1.3, 12.1, 1.65, "FFF5F5", C.danger);
  slide.addShape("roundRect", { x: 0.8, y: 1.4, w: 0.7, h: 0.35, fill: { color: C.danger }, rectRadius: 0.05 });
  slide.addText("P0", { x: 0.8, y: 1.4, w: 0.7, h: 0.35, fontSize: 12, fontFace: "Arial", bold: true, color: C.textLight, align: "center", valign: "middle" });
  slide.addText("必须修复 — 上生产前阻断", { x: 1.6, y: 1.4, w: 5, h: 0.35, fontSize: 11, fontFace: "Arial", bold: true, color: C.danger, valign: "middle" });
  const p0Items = [
    "pom.xml maven-compiler-plugin 配置 source/target=5（Java 5！）→ 删除或改为 17",
    "MCP Token AES-GCM 主密钥存于 system_config 表 → 迁移到环境变量 / KMS",
  ];
  p0Items.forEach((item, i) => {
    slide.addText("!", { x: 0.85, y: 1.85 + i * 0.5, w: 0.3, h: 0.35, fontSize: 14, bold: true, color: C.danger, valign: "middle" });
    slide.addText(item, { x: 1.2, y: 1.85 + i * 0.5, w: 11.2, h: 0.35, fontSize: 10, fontFace: "Arial", color: C.textDark, valign: "middle" });
  });

  addCard(slide, 0.6, 3.1, 12.1, 2.15, "FFFBF0", C.accent2);
  slide.addShape("roundRect", { x: 0.8, y: 3.2, w: 0.7, h: 0.35, fill: { color: C.accent2 }, rectRadius: 0.05 });
  slide.addText("P1", { x: 0.8, y: 3.2, w: 0.7, h: 0.35, fontSize: 12, fontFace: "Arial", bold: true, color: C.textLight, align: "center", valign: "middle" });
  slide.addText("建议尽快处理 — 影响工程化质量", { x: 1.6, y: 3.2, w: 6, h: 0.35, fontSize: 11, fontFace: "Arial", bold: true, color: C.accent2, valign: "middle" });
  const p1Items = [
    "大量改动未提交：初始 commit 后所有迭代堆在工作区 → 按功能分批 commit",
    "默认管理员凭据 admin/admin123 → 生产环境必须强改",
    "DeepSeek 模型名不一致：.env.example 为 deepseek-v4-pro，application.yml 默认 deepseek-chat → 统一",
    "测试覆盖偏低：backend 仅 3 个测试类，agent-service 仅 1 个 → 补核心编排与 MCP 适配测试",
  ];
  p1Items.forEach((item, i) => {
    slide.addText("-", { x: 0.85, y: 3.65 + i * 0.38, w: 0.3, h: 0.3, fontSize: 12, color: C.accent2, valign: "middle" });
    slide.addText(item, { x: 1.2, y: 3.65 + i * 0.38, w: 11.2, h: 0.3, fontSize: 10, fontFace: "Arial", color: C.textDark, valign: "middle" });
  });

  addCard(slide, 0.6, 5.4, 12.1, 1.4, C.lightBg, C.lightGray);
  slide.addShape("roundRect", { x: 0.8, y: 5.5, w: 0.7, h: 0.35, fill: { color: C.midGray }, rectRadius: 0.05 });
  slide.addText("P2", { x: 0.8, y: 5.5, w: 0.7, h: 0.35, fontSize: 12, fontFace: "Arial", bold: true, color: C.textLight, align: "center", valign: "middle" });
  slide.addText("持续改进 — 功能与体验优化", { x: 1.6, y: 5.5, w: 6, h: 0.35, fontSize: 11, fontFace: "Arial", bold: true, color: C.midGray, valign: "middle" });
  const p2Items = [
    "Agent 智能性有限：技能分类与工具选择均为关键词匹配 → 引入 LLM-based 路由",
    "DebugController 暴露调试端点 → 生产环境禁用或权限收敛",
  ];
  p2Items.forEach((item, i) => {
    slide.addText("*", { x: 0.85, y: 5.95 + i * 0.32, w: 0.3, h: 0.28, fontSize: 10, color: C.midGray, valign: "middle" });
    slide.addText(item, { x: 1.2, y: 5.95 + i * 0.32, w: 11.2, h: 0.28, fontSize: 10, fontFace: "Arial", color: C.textDark, valign: "middle" });
  });

  addFooter(slide, 7);
}

// ══════════════════════════════════════════════
// SLIDE 8: What to Do Next - Consistency
// ══════════════════════════════════════════════
{
  const slide = pptx.addSlide();
  addSlideBg(slide);
  addHeaderBar(slide, "以后我该怎么做", "方法论一致性 — 7 层保障方案");

  addCard(slide, 0.6, 1.3, 12.1, 0.8, C.dark, null);
  slide.addText([
    { text: "核心诉求：", options: { bold: true, color: "F5A623", fontSize: 12 } },
    { text: "  同一个问题在不同时间提问，Agent 应得出一致的结论（推理路径一致、输出结构一致）", options: { color: C.textLight, fontSize: 12 } },
  ], { x: 0.8, y: 1.35, w: 11.7, h: 0.7, fontFace: "Arial", valign: "middle" });

  const layers = [
    { num: "1", title: "确定性流水线优先", desc: "classify → scope → plan 保持纯关键词逻辑；模型只参与摘要增强", color: "0F4C81" },
    { num: "2", title: "Temperature = 0 + 固定 Seed", desc: "DeepSeek 调用 temperature 设为 0；如模型支持 seed 则传入固定值", color: "00A6A6" },
    { num: "3", title: "结构化答案模板", desc: "每个 Skill 绑定 answerTemplate，模型输出必须遵循模板结构", color: "F5A623" },
    { num: "4", title: "查询结果缓存层", desc: "同一 (question_hash + project_ids + skill_id) 在 TTL 窗口内命中缓存", color: "2D9D5F" },
    { num: "5", title: "Prompt 版本管理", desc: "systemPrompt / answerTemplate 纳入版本控制，审计日志记录 prompt 版本", color: "E84855" },
    { num: "6", title: "Golden Test 回归套件", desc: "20+ 黄金测试用例，断言技能 + 工具 + 答案结构，CI 自动运行", color: "6B7B8D" },
    { num: "7", title: "完整审计可回放", desc: "三级审计记录全链路（技能/工具/模型/prompt版本/参数），支持按 requestId 回放", color: "0F4C81" },
  ];

  layers.forEach((layer, i) => {
    const y = 2.25 + i * 0.72;
    addCard(slide, 0.6, y, 12.1, 0.62, C.lightBg, C.lightGray);
    slide.addShape("ellipse", { x: 0.75, y: y + 0.08, w: 0.45, h: 0.45, fill: { color: layer.color } });
    slide.addText(layer.num, { x: 0.75, y: y + 0.08, w: 0.45, h: 0.45, fontSize: 14, fontFace: "Arial", bold: true, color: C.textLight, align: "center", valign: "middle" });
    slide.addText(layer.title, { x: 1.35, y: y + 0.05, w: 3.5, h: 0.3, fontSize: 12, fontFace: "Arial", bold: true, color: C.textDark, valign: "middle" });
    slide.addText(layer.desc, { x: 1.35, y: y + 0.35, w: 11.0, h: 0.25, fontSize: 9, fontFace: "Arial", color: C.midGray, valign: "middle" });
  });

  addFooter(slide, 8);
}

// ══════════════════════════════════════════════
// SLIDE 9: What to Do Next - Roadmap
// ══════════════════════════════════════════════
{
  const slide = pptx.addSlide();
  addSlideBg(slide);
  addHeaderBar(slide, "以后我该怎么做", "技术路线与演进规划 — 三阶段");

  const phases = [
    { phase: "Phase 1", title: "MVP 稳定化", time: "当前 ~ +1月", color: "E84855", status: "进行中",
      items: ["修复 pom.xml 编译配置", "提交存量改动，分批 commit", "MCP 主密钥迁移环境变量", "默认凭据强改", "统一 DeepSeek 模型名", "补核心链路测试", "Docker 化部署"] },
    { phase: "Phase 2", title: "工程化与一致性", time: "+1 ~ +3月", color: "F5A623", status: "规划中",
      items: ["Temperature=0 + 固定 Seed", "结构化答案模板", "查询缓存层（Redis）", "Prompt 版本管理", "Golden Test 回归套件", "DebugController 权限收敛", "LLM-based 技能路由"] },
    { phase: "Phase 3", title: "通用 Agent 平台", time: "+3 ~ +6月", color: "00A6A6", status: "远期",
      items: ["MCP 工具配置后台", "Agent Prompt 模板管理 UI", "多入口接入（Web/企微/钉钉）", "多步工具调用编排", "项目风险主动监控", "自动日报/周报", "多模型支持"] },
  ];

  phases.forEach((p, i) => {
    const x = 0.6 + i * 4.2;
    addCard(slide, x, 1.3, 3.9, 5.6, C.lightBg, p.color);
    slide.addShape("roundRect", { x, y: 1.3, w: 3.9, h: 0.85, fill: { color: p.color }, rectRadius: 0.08 });
    slide.addText(p.phase, { x: x + 0.15, y: 1.33, w: 2.5, h: 0.3, fontSize: 11, fontFace: "Arial", color: C.textLight, valign: "middle" });
    slide.addText(p.title, { x: x + 0.15, y: 1.6, w: 2.8, h: 0.35, fontSize: 14, fontFace: "Arial", bold: true, color: C.textLight, valign: "middle" });
    slide.addShape("roundRect", { x: x + 2.7, y: 1.38, w: 1.0, h: 0.25, fill: { color: C.textLight, transparency: 75 }, rectRadius: 0.03 });
    slide.addText(p.status, { x: x + 2.7, y: 1.38, w: 1.0, h: 0.25, fontSize: 8, fontFace: "Arial", bold: true, color: C.textLight, align: "center", valign: "middle" });
    slide.addText("[ " + p.time + " ]", { x: x + 0.15, y: 2.25, w: 3.5, h: 0.3, fontSize: 10, fontFace: "Arial", color: C.midGray, italic: true });
    p.items.forEach((item, j) => {
      slide.addShape("ellipse", { x: x + 0.2, y: 2.75 + j * 0.48, w: 0.1, h: 0.1, fill: { color: p.color } });
      slide.addText(item, { x: x + 0.4, y: 2.7 + j * 0.48, w: 3.3, h: 0.4, fontSize: 9, fontFace: "Arial", color: C.textDark, valign: "middle" });
    });
    if (i < phases.length - 1) {
      slide.addText("→", { x: x + 3.85, y: 3.5, w: 0.4, h: 0.5, fontSize: 22, color: C.midGray, align: "center", valign: "middle", bold: true });
    }
  });

  addFooter(slide, 9);
}

// ══════════════════════════════════════════════
// SLIDE 10: Summary
// ══════════════════════════════════════════════
{
  const slide = pptx.addSlide();
  addSlideBg(slide, C.dark);
  slide.addShape("rect", { x: 0, y: 0, w: 13.33, h: 0.12, fill: { color: C.accent } });
  slide.addShape("rect", { x: 0, y: 7.38, w: 13.33, h: 0.12, fill: { color: C.accent } });
  slide.addShape("ellipse", { x: -1, y: -1, w: 3, h: 3, fill: { color: C.primary, transparency: 80 } });
  slide.addShape("ellipse", { x: 11, y: 5, w: 3, h: 3, fill: { color: C.accent, transparency: 80 } });

  slide.addText("总结", { x: 0.6, y: 0.6, w: 8, h: 0.6, fontSize: 28, fontFace: "Arial", bold: true, color: C.textLight });
  slide.addShape("rect", { x: 0.6, y: 1.15, w: 3, h: 0.04, fill: { color: C.accent2 } });

  const summaries = [
    { title: "项目现状", text: "架构清晰、职责分明的 MVP 已基本成型。Java 安全边界 + Python 智能层 + Vue 管理后台，三级审计齐全，异步化设计周到。", color: "00A6A6" },
    { title: "优化方向", text: "P0 修编译配置 + 迁密钥 → P1 提交存量 + 强改凭据 + 补测试 → P2 升级智能路由 + 收敛调试接口。", color: "F5A623" },
    { title: "一致性保障", text: "7 层方案：确定性流水线 + temperature=0 + 模板化输出 + 查询缓存 + Prompt 版本管理 + Golden Test + 审计回放。", color: "2D9D5F" },
    { title: "技术路线", text: "Phase 1 MVP 稳定化 → Phase 2 工程化与一致性 → Phase 3 通用 Agent 平台（多入口、多步工具、主动监控）。", color: "0F4C81" },
  ];

  summaries.forEach((s, i) => {
    const y = 1.5 + i * 1.35;
    slide.addShape("roundRect", { x: 0.6, y, w: 12.1, h: 1.2, fill: { color: C.dark, transparency: 50 }, line: { color: s.color, width: 1.5 }, rectRadius: 0.08 });
    slide.addShape("rect", { x: 0.6, y, w: 0.08, h: 1.2, fill: { color: s.color } });
    slide.addText(s.title, { x: 0.85, y: y + 0.1, w: 3, h: 0.35, fontSize: 14, fontFace: "Arial", bold: true, color: s.color, valign: "middle" });
    slide.addText(s.text, { x: 0.85, y: y + 0.45, w: 11.6, h: 0.65, fontSize: 11, fontFace: "Arial", color: C.textLight, valign: "middle" });
  });

  slide.addShape("roundRect", { x: 0.6, y: 6.5, w: 12.1, h: 0.65, fill: { color: "F5A623", transparency: 70 }, line: { color: "F5A623", width: 1 }, rectRadius: 0.06 });
  slide.addText([
    { text: "立即行动：", options: { bold: true, color: "F5A623", fontSize: 11 } },
    { text: "  ① 修 pom.xml 编译配置   ② 提交存量改动   ③ 迁移 MCP 主密钥   ④ 强改默认凭据   ⑤ 补核心链路测试", options: { color: C.textLight, fontSize: 10 } },
  ], { x: 0.8, y: 6.55, w: 11.7, h: 0.55, fontFace: "Arial", valign: "middle" });

  addFooter(slide, 10);
}

const outputPath = path.join(__dirname, "Lark_Connect_我做了什么与以后怎么做.pptx");
pptx.writeFile({ fileName: outputPath }).then(() => {
  console.log("PPT generated: " + outputPath);
}).catch(err => {
  console.error("Error:", err);
  process.exit(1);
});
