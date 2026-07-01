const PptxGenJS = require("pptxgenjs");
const path = require("path");

const pptx = new PptxGenJS();
pptx.layout = "LAYOUT_WIDE";
pptx.author = "Lark Connect Team";
pptx.company = "Primelayer";
pptx.subject = 'Lark Connect 智能助手 · 能力盘点与升级路线';

const C = {
  primary: "1A56DB",
  accent: "0EA5E9",
  accent2: "F59E0B",
  danger: "EF4444",
  success: "10B981",
  dark: "1E293B",
  bg: "FFFFFF",
  lightBg: "F1F5F9",
  lightGray: "E2E8F0",
  midGray: "64748B",
  textDark: "334155",
  textLight: "FFFFFF",
  textMuted: "94A3B8",
  purple: "8B5CF6",
};

function addSlideBg(slide, color) { slide.background = { color: color || C.bg }; }

function addHeaderBar(slide, title, subtitle) {
  slide.addShape("rect", { x: 0, y: 0, w: 13.33, h: 0.06, fill: { color: C.primary } });
  slide.addText(title, { x: 0.6, y: 0.2, w: 10, h: 0.5, fontSize: 24, fontFace: "Microsoft YaHei", bold: true, color: C.primary });
  if (subtitle) {
    slide.addText(subtitle, { x: 0.6, y: 0.65, w: 12, h: 0.28, fontSize: 12, fontFace: "Arial", color: C.midGray, italic: true });
  }
}

function addFooter(slide, pageNum) {
  slide.addShape("rect", { x: 0, y: 7.35, w: 13.33, h: 0.02, fill: { color: C.lightGray } });
  slide.addText('Lark Connect 智能助手', { x: 0.6, y: 7.05, w: 6, h: 0.3, fontSize: 9, fontFace: "Microsoft YaHei", color: C.textMuted });
  slide.addText(String(pageNum), { x: 12.4, y: 7.05, w: 0.6, h: 0.3, fontSize: 9, fontFace: "Arial", color: C.textMuted, align: "right" });
}

function addCard(slide, x, y, w, h, fillColor, borderColor) {
  slide.addShape("roundRect", { x, y, w, h, fill: { color: fillColor }, line: borderColor ? { color: borderColor, width: 1 } : undefined, rectRadius: 0.08 });
}

// ===== SLIDE 1: Title =====
{
  const slide = pptx.addSlide();
  addSlideBg(slide, C.dark);
  slide.addShape("rect", { x: 0, y: 0, w: 13.33, h: 0.12, fill: { color: C.primary } });
  slide.addShape("rect", { x: 0, y: 7.38, w: 13.33, h: 0.12, fill: { color: C.primary } });
  slide.addShape("ellipse", { x: 10.5, y: 0.8, w: 2.5, h: 2.5, fill: { color: C.accent, transparency: 85 } });
  slide.addShape("ellipse", { x: 11.2, y: 1.5, w: 1.5, h: 1.5, fill: { color: C.accent2, transparency: 80 } });
  slide.addText('Lark Connect 智能助手', { x: 0.8, y: 1.5, w: 11, h: 1.0, fontSize: 40, fontFace: "Microsoft YaHei", bold: true, color: C.textLight });
  slide.addText('能力盘点与升级路线', { x: 0.8, y: 2.5, w: 11, h: 0.6, fontSize: 26, fontFace: "Microsoft YaHei", color: C.accent });
  slide.addShape("rect", { x: 0.8, y: 3.2, w: 3.5, h: 0.04, fill: { color: C.accent2 } });
  slide.addText('我们现在能做什么  |  还有哪些短板  |  怎样一步步变强', { x: 0.8, y: 3.4, w: 11, h: 0.5, fontSize: 15, fontFace: "Microsoft YaHei", color: C.textMuted });
  slide.addText('飞书 + Primelayer + DeepSeek\n打通项目管理系统与即时通讯，让用户用一句话就能查到项目数据', { x: 0.8, y: 4.3, w: 11, h: 0.8, fontSize: 12, fontFace: "Microsoft YaHei", color: C.textMuted });
  slide.addText('2026年7月1日', { x: 0.8, y: 6.0, w: 4, h: 0.4, fontSize: 13, fontFace: "Microsoft YaHei", color: C.accent });
  addFooter(slide, 1);
}

// ===== SLIDE 2: What We Built =====
{
  const slide = pptx.addSlide();
  addSlideBg(slide);
  addHeaderBar(slide, '我们做了什么', '一句话概括：飞书里问一句，系统帮你查数据、写报告');

  addCard(slide, 0.6, 1.2, 12.1, 1.2, C.dark, null);
  slide.addText([
    { text: '一句话说明：', options: { bold: true, color: C.accent2, fontSize: 14 } },
    { text: '  在飞书里打字问一句「项目进度怎么样」，系统自动查 Primelayer 的项目数据，把结果整理成你能看懂的回答发回来。', options: { color: C.textLight, fontSize: 14 } },
  ], { x: 0.8, y: 1.25, w: 11.7, h: 1.1, fontFace: "Microsoft YaHei", valign: "middle" });

  const pillars = [
    { icon: "1", title: '自然语言入口', desc: '用户在飞书里用日常语言提问，\n不需要学任何系统操作', color: C.primary },
    { icon: "2", title: '自动查项目数据', desc: '系统从 Primelayer 项目管理平台\n自动拉取施工日报、进度、风险等', color: C.accent },
    { icon: "3", title: '智能整理回答', desc: 'DeepSeek 大模型把原始数据\n整理成结构化的结论和建议', color: C.accent2 },
    { icon: "4", title: '安全可控', desc: '项目密钥只在后台使用，\n智能层永远接触不到', color: C.success },
  ];

  pillars.forEach((p, i) => {
    const x = 0.6 + i * 3.1;
    addCard(slide, x, 2.6, 2.8, 2.4, C.lightBg, p.color);
    slide.addShape("ellipse", { x: x + 0.8, y: 2.7, w: 0.7, h: 0.7, fill: { color: p.color } });
    slide.addText(p.icon, { x: x + 0.8, y: 2.7, w: 0.7, h: 0.7, fontSize: 22, fontFace: "Arial", bold: true, color: C.textLight, align: "center", valign: "middle" });
    slide.addText(p.title, { x: x + 0.2, y: 3.5, w: 2.4, h: 0.35, fontSize: 13, fontFace: "Microsoft YaHei", bold: true, color: p.color, align: "center", valign: "middle" });
    slide.addText(p.desc, { x: x + 0.2, y: 3.85, w: 2.4, h: 0.95, fontSize: 10, fontFace: "Microsoft YaHei", color: C.textDark, align: "center", valign: "top" });
  });

  addCard(slide, 0.6, 5.2, 12.1, 1.8, C.lightBg, C.lightGray);
  slide.addText('流程简述', { x: 0.8, y: 5.3, w: 4, h: 0.3, fontSize: 13, fontFace: "Microsoft YaHei", bold: true, color: C.primary });

  const flowSteps = [
    { label: '飞书提问', color: C.accent },
    { label: '自动分类意图', color: C.primary },
    { label: '锁定项目范围', color: C.accent2 },
    { label: '调用数据工具', color: C.danger },
    { label: '整理回答', color: C.success },
    { label: '飞书回复', color: C.accent },
  ];

  flowSteps.forEach((step, i) => {
    const sx = 0.8 + i * 2.0;
    slide.addShape("roundRect", { x: sx, y: 5.65, w: 1.8, h: 0.55, fill: { color: step.color }, rectRadius: 0.06 });
    slide.addText(step.label, { x: sx, y: 5.65, w: 1.8, h: 0.55, fontSize: 10, fontFace: "Microsoft YaHei", bold: true, color: C.textLight, align: "center", valign: "middle" });
    if (i < flowSteps.length - 1) {
      slide.addText("→", { x: sx + 1.75, y: 5.7, w: 0.35, h: 0.4, fontSize: 14, color: C.midGray, align: "center", valign: "middle", bold: true });
    }
  });

  slide.addText('整个过程不需要人工介入，用户只管提问，系统全程自动完成', { x: 0.8, y: 6.35, w: 11.7, h: 0.3, fontSize: 10, fontFace: "Microsoft YaHei", color: C.midGray, italic: true });
  addFooter(slide, 2);
}

// ===== SLIDE 3: Current Capabilities =====
{
  const slide = pptx.addSlide();
  addSlideBg(slide);
  addHeaderBar(slide, '目前能回答哪些问题', '5 个技能覆盖项目管理的常见场景');

  const skills = [
    { icon: "1", name: '项目报告与施工情况', examples: '今日施工情况、质量安全、隐患排查、项目进度', canDo: '查 Primelayer 施工日报/周报/质量/安全表单，整合出报告', cannotDo: '无法对比历史趋势，无法主动发现问题', color: C.primary },
    { icon: "2", name: '项目状态问答', examples: '项目怎么样、当前进度、健康度', canDo: '查项目健康度和状态指标，给出结论和建议', cannotDo: '无法跨项目对比，无法判断指标变化方向', color: C.accent },
    { icon: "3", name: '任务风险问答', examples: '逾期任务、风险、待办、负责人', canDo: '查任务列表和风险项，按优先级整理', cannotDo: '无法预测风险趋势，无法做跨项目风险汇总', color: C.accent2 },
    { icon: "4", name: '日报周报生成', examples: '本周周报、日报总结、阶段汇报', canDo: '自动查项目健康度和任务数据，拼接成报告', cannotDo: '无法做深度分析，无法加入现场核查结论', color: C.success },
    { icon: "5", name: '通用问题兜底', examples: '帮我看看XX、帮我查一下', canDo: '兜底处理任何只读查询，给出简单回答', cannotDo: '无法精准匹配工具，回答可能不准确', color: C.midGray },
  ];

  skills.forEach((s, i) => {
    const y = 1.05 + i * 1.2;
    addCard(slide, 0.6, y, 12.1, 1.1, C.lightBg, s.color);
    slide.addShape("roundRect", { x: 0.7, y: y + 0.15, w: 0.5, h: 0.5, fill: { color: s.color }, rectRadius: 0.05 });
    slide.addText(s.icon, { x: 0.7, y: y + 0.15, w: 0.5, h: 0.5, fontSize: 18, fontFace: "Arial", bold: true, color: C.textLight, align: "center", valign: "middle" });
    slide.addText(s.name, { x: 1.35, y: y + 0.08, w: 3.0, h: 0.35, fontSize: 12, fontFace: "Microsoft YaHei", bold: true, color: s.color, valign: "middle" });
    slide.addText('触发示例：' + s.examples, { x: 1.35, y: y + 0.42, w: 3.0, h: 0.55, fontSize: 9, fontFace: "Microsoft YaHei", color: C.textDark, valign: "top" });
    slide.addText([
      { text: '能做：', options: { bold: true, color: C.success, fontSize: 9 } },
      { text: s.canDo, options: { color: C.textDark, fontSize: 9 } },
    ], { x: 4.5, y: y + 0.08, w: 4.0, h: 0.5, fontFace: "Microsoft YaHei", valign: "top" });
    slide.addText([
      { text: '还做不了：', options: { bold: true, color: C.danger, fontSize: 9 } },
      { text: s.cannotDo, options: { color: C.textDark, fontSize: 9 } },
    ], { x: 8.6, y: y + 0.08, w: 4.0, h: 0.5, fontFace: "Microsoft YaHei", valign: "top" });
    const progress = i === 4 ? 0.3 : i === 0 ? 0.7 : 0.5;
    slide.addShape("roundRect", { x: 4.5, y: y + 0.7, w: 7.8, h: 0.25, fill: { color: C.lightGray }, rectRadius: 0.03 });
    slide.addShape("roundRect", { x: 4.5, y: y + 0.7, w: 7.8 * progress, h: 0.25, fill: { color: s.color }, rectRadius: 0.03 });
    slide.addText('完成度', { x: 12.4, y: y + 0.68, w: 0.8, h: 0.3, fontSize: 8, fontFace: "Microsoft YaHei", color: C.midGray, align: "right", valign: "middle" });
  });

  addFooter(slide, 3);
}

// ===== SLIDE 4: Pain Points =====
{
  const slide = pptx.addSlide();
  addSlideBg(slide);
  addHeaderBar(slide, '目前的短板在哪里', '不是代码问题，是用户感受不到理想效果');

  const pains = [
    { category: '听不懂', title: '用户换个说法，系统就不认识', businessImpact: '用户说「项目进展如何」可能匹配不到「进度」关键词，\n被迫重新措辞，体验打折', currentCause: '靠关键词子串匹配来理解意图', enhancement: '改用语义理解——同义词、模糊表达都能识别', priority: "P0", color: C.danger },
    { category: '找不准', title: '项目名称说个简称就匹配不上', businessImpact: '项目叫「北极星」，用户说「星项目」，\n系统找不到对应项目，只能让用户补全名称', currentCause: '项目名只做精确子串匹配', enhancement: '改用语义匹配 + 记住用户常用的项目', priority: "P1", color: C.accent2 },
    { category: '干太少', title: '复杂问题只能调一个工具', businessImpact: '用户问「对比A和B项目健康度和逾期任务数」，\n系统只能查一个维度的数据，无法组合分析', currentCause: '每次只选一个工具调用', enhancement: '改用多工具编排——自动规划需要调哪些工具、按什么顺序', priority: "P1", color: C.accent },
    { category: '说太糙', title: '回答只是数据堆砌，缺乏分析深度', businessImpact: '用户拿到一堆原始数据列表，\n而不是「结论→风险→建议」的结构化判断', currentCause: '大模型把所有数据塞进一个 Prompt 生成回答', enhancement: '结构化输出模板 + 推理链引导 + 前端富文本渲染', priority: "P0", color: C.danger },
    { category: '没记忆', title: '每次对话都从零开始，不会追问', businessImpact: '用户上次问过「北极星项目」，下次再问还得说全名；\n系统不会主动追问「你想查哪个项目」', currentCause: '无会话记忆，无多轮对话循环', enhancement: '引入会话记忆 + 人机循环（追问→回答→追问）', priority: "P1", color: C.accent2 },
  ];

  pains.forEach((p, i) => {
    const y = 1.1 + i * 1.2;
    addCard(slide, 0.6, y, 12.1, 1.1, C.lightBg, p.color);
    slide.addShape("roundRect", { x: 0.7, y: y + 0.15, w: 0.55, h: 0.35, fill: { color: p.color }, rectRadius: 0.05 });
    slide.addText(p.priority, { x: 0.7, y: y + 0.15, w: 0.55, h: 0.35, fontSize: 10, fontFace: "Arial", bold: true, color: C.textLight, align: "center", valign: "middle" });
    slide.addText(p.category + '：' + p.title, { x: 1.4, y: y + 0.08, w: 4.5, h: 0.35, fontSize: 13, fontFace: "Microsoft YaHei", bold: true, color: p.color, valign: "middle" });
    slide.addText(p.businessImpact, { x: 1.4, y: y + 0.45, w: 3.5, h: 0.55, fontSize: 9, fontFace: "Microsoft YaHei", color: C.textDark, valign: "top" });
    slide.addText([
      { text: '原因：', options: { bold: true, fontSize: 9, color: C.midGray } },
      { text: p.currentCause, options: { fontSize: 9, color: C.midGray } },
    ], { x: 5.0, y: y + 0.08, w: 2.5, h: 0.4, fontFace: "Microsoft YaHei", valign: "top" });
    slide.addText([
      { text: '升级方向：', options: { bold: true, fontSize: 9, color: C.success } },
      { text: p.enhancement, options: { fontSize: 9, color: C.textDark } },
    ], { x: 7.6, y: y + 0.08, w: 5.0, h: 0.9, fontFace: "Microsoft YaHei", valign: "top" });
  });

  addFooter(slide, 4);
}

// ===== SLIDE 5: Roadmap Overview =====
{
  const slide = pptx.addSlide();
  addSlideBg(slide);
  addHeaderBar(slide, '升级路线总览', '从「按规则干活」到「能推理、会记忆」');

  addCard(slide, 0.6, 1.1, 12.1, 0.7, C.dark, null);
  slide.addText([
    { text: '终极目标：', options: { bold: true, color: C.accent2, fontSize: 13 } },
    { text: '  用户随便怎么问，系统都能听懂、找准、干完、说清，还能记住偏好、主动提醒', options: { color: C.textLight, fontSize: 13 } },
  ], { x: 0.8, y: 1.15, w: 11.7, h: 0.6, fontFace: "Microsoft YaHei", valign: "middle" });

  const phases = [
    { phase: '第一阶段', title: '听得懂、说得清', time: '1-2 周', status: '立即启动', color: C.danger, outcomes: ['同义/模糊表达都能正确识别意图', '回答有「结论→风险→建议」的清晰结构', '工具选择从关键词匹配升级为语义检索'], efforts: ['技能分类：引入 LLM 零样本分类', '答案生成：Prompt 加入推理链引导', '工具选择：语义向量检索 top-k'] },
    { phase: '第二阶段', title: '找得准、干得多', time: '1-2 月', status: '中期推进', color: C.accent2, outcomes: ['简称、模糊项目名也能自动匹配', '复杂问题能组合多个工具分步完成', '用户不用每次重复说项目名', '前端能展示表格、卡片等富文本'], efforts: ['项目范围：语义匹配 + 会话记忆', '工具编排：ReAct 多步规划模式', '答案输出：JSON Schema 结构化', '流程控制：条件分支 + 追问机制'] },
    { phase: '第三阶段', title: '会记忆、能预判', time: '3-6 月', status: '远期规划', color: C.accent, outcomes: ['系统记住用户常用项目和偏好', '自动发现用户的新需求模式', '接入行业知识库，回答更有深度', 'A/B 测试自动评估回答质量'], efforts: ['用户画像与偏好记忆', '自动技能发现（从历史日志聚类）', '领域知识库 RAG 接入', 'A/B 测试与自动评估体系'] },
  ];

  phases.forEach((p, i) => {
    const x = 0.6 + i * 4.2;
    addCard(slide, x, 1.95, 3.9, 5.0, C.lightBg, p.color);
    slide.addShape("roundRect", { x, y: 1.95, w: 3.9, h: 0.8, fill: { color: p.color }, rectRadius: 0.08 });
    slide.addText(p.phase, { x: x + 0.15, y: 2.0, w: 2.5, h: 0.25, fontSize: 10, fontFace: "Microsoft YaHei", color: C.textLight, valign: "middle" });
    slide.addText(p.title, { x: x + 0.15, y: 2.2, w: 2.5, h: 0.35, fontSize: 14, fontFace: "Microsoft YaHei", bold: true, color: C.textLight, valign: "middle" });
    slide.addShape("roundRect", { x: x + 2.6, y: 2.05, w: 1.1, h: 0.25, fill: { color: C.textLight, transparency: 75 }, rectRadius: 0.03 });
    slide.addText(p.status, { x: x + 2.6, y: 2.05, w: 1.1, h: 0.25, fontSize: 8, fontFace: "Microsoft YaHei", bold: true, color: C.textLight, align: "center", valign: "middle" });
    slide.addText(p.time, { x: x + 0.15, y: 2.85, w: 3.5, h: 0.25, fontSize: 10, fontFace: "Microsoft YaHei", color: C.midGray, italic: true });
    slide.addText('业务效果', { x: x + 0.15, y: 3.2, w: 3.5, h: 0.25, fontSize: 10, fontFace: "Microsoft YaHei", bold: true, color: p.color });
    p.outcomes.forEach((item, j) => {
      slide.addShape("ellipse", { x: x + 0.2, y: 3.5 + j * 0.35, w: 0.1, h: 0.1, fill: { color: C.success } });
      slide.addText(item, { x: x + 0.4, y: 3.45 + j * 0.35, w: 3.3, h: 0.3, fontSize: 9, fontFace: "Microsoft YaHei", color: C.textDark, valign: "middle" });
    });
    const effortStartY = 3.5 + p.outcomes.length * 0.35 + 0.15;
    slide.addText('技术手段', { x: x + 0.15, y: effortStartY, w: 3.5, h: 0.25, fontSize: 9, fontFace: "Microsoft YaHei", bold: true, color: C.midGray });
    p.efforts.forEach((item, j) => {
      slide.addText('▸ ' + item, { x: x + 0.2, y: effortStartY + 0.25 + j * 0.28, w: 3.5, h: 0.25, fontSize: 8, fontFace: "Microsoft YaHei", color: C.textDark, valign: "middle" });
    });
    if (i < phases.length - 1) {
      slide.addText("→", { x: x + 3.85, y: 3.8, w: 0.4, h: 0.5, fontSize: 22, color: C.midGray, align: "center", valign: "middle", bold: true });
    }
  });

  addFooter(slide, 5);
}

// ===== SLIDE 6: Module Upgrade Comparison =====
{
  const slide = pptx.addSlide();
  addSlideBg(slide);
  addHeaderBar(slide, '5 大模块升级对照', '每个模块：现在怎么做 → 升级后怎么做 → 业务效果');

  const modules = [
    { name: '意图理解', now: '关键词子串匹配\n说「进度」才能触发，说「进展」就漏掉', future: '语义分类\n换个说法也能识别，\n不确定时主动追问', effect: '意图识别准确率大幅提升', color: C.danger },
    { name: '项目匹配', now: '项目名精确子串匹配\n简称、别名、缩写都不认', future: '语义匹配 + 记住偏好\n简称也能匹配，\n默认回到用户常用项目', effect: '追问减少，体验更顺畅', color: C.accent },
    { name: '工具选择', now: '关键词打分选一个工具\n复杂问题只查一个维度', future: 'ReAct 多步规划\n自动组合多个工具，\n按顺序逐步完成', effect: '能回答更复杂、更综合的问题', color: C.accent2 },
    { name: '答案生成', now: '大模型自由组织回答\n纯文本堆砌，没有结构', future: '结构化输出模板\n结论→风险→建议→数据范围\n前端可渲染表格和卡片', effect: '回答有逻辑、可读性强', color: C.success },
    { name: '对话流程', now: '线性流水线，一问一答\n不会追问，没有循环', future: '条件分支 + 追问循环\n不确定就追问，\n复杂问题分步引导', effect: '对话更像真人助理', color: C.purple },
  ];

  const headers = ['模块', '现在怎么做', '升级后怎么做', '业务效果'];
  const headerWidths = [1.5, 3.5, 3.5, 2.8];
  const headerX = [0.6, 2.1, 5.6, 9.1];

  headers.forEach((h, i) => {
    slide.addShape("roundRect", { x: headerX[i], y: 1.1, w: headerWidths[i], h: 0.4, fill: { color: C.primary }, rectRadius: 0.03 });
    slide.addText(h, { x: headerX[i], y: 1.1, w: headerWidths[i], h: 0.4, fontSize: 11, fontFace: "Microsoft YaHei", bold: true, color: C.textLight, align: "center", valign: "middle" });
  });

  modules.forEach((m, i) => {
    const y = 1.6 + i * 1.1;
    addCard(slide, 0.6, y, 1.5, 1.0, m.color, null);
    slide.addText(m.name, { x: 0.6, y: y, w: 1.5, h: 1.0, fontSize: 12, fontFace: "Microsoft YaHei", bold: true, color: C.textLight, align: "center", valign: "middle" });
    addCard(slide, 2.1, y, 3.5, 1.0, "FFF5F5", C.danger);
    slide.addText(m.now, { x: 2.2, y: y + 0.05, w: 3.3, h: 0.9, fontSize: 9, fontFace: "Microsoft YaHei", color: C.textDark, valign: "top" });
    addCard(slide, 5.6, y, 3.5, 1.0, "F0FFF4", C.success);
    slide.addText(m.future, { x: 5.7, y: y + 0.05, w: 3.3, h: 0.9, fontSize: 9, fontFace: "Microsoft YaHei", color: C.textDark, valign: "top" });
    addCard(slide, 9.1, y, 2.8, 1.0, C.lightBg, m.color);
    slide.addText(m.effect, { x: 9.2, y: y + 0.05, w: 2.6, h: 0.9, fontSize: 10, fontFace: "Microsoft YaHei", bold: true, color: m.color, valign: "middle" });
    slide.addText("→", { x: 5.3, y: y + 0.3, w: 0.4, h: 0.4, fontSize: 16, color: C.accent2, align: "center", valign: "middle", bold: true });
  });

  addFooter(slide, 6);
}

// ===== SLIDE 7: Priority Actions =====
{
  const slide = pptx.addSlide();
  addSlideBg(slide);
  addHeaderBar(slide, '优先行动清单', '请老板确认优先级和资源投入');

  slide.addText('立即要做（1-2 周）', { x: 0.6, y: 1.1, w: 12, h: 0.35, fontSize: 14, fontFace: "Microsoft YaHei", bold: true, color: C.danger });
  slide.addShape("rect", { x: 0.6, y: 1.42, w: 2.5, h: 0.03, fill: { color: C.danger } });

  const immediate = [
    { action: '意图理解升级', desc: '从关键词匹配改为语义分类，让系统真正「听懂」', impact: '用户不再需要反复措辞', owner: '智能层开发', color: C.danger },
    { action: '回答结构化', desc: '回答必须遵循「结论→风险→建议→数据范围」模板', impact: '用户拿到的是判断而不是数据堆砌', owner: '智能层+前端', color: C.danger },
    { action: '工具语义检索', desc: '从关键词打分改为语义匹配 top-k，再由模型精选', impact: '工具选择更准，减少无关调用', owner: '智能层开发', color: C.accent2 },
  ];

  immediate.forEach((item, i) => {
    const y = 1.6 + i * 0.9;
    addCard(slide, 0.6, y, 12.1, 0.8, C.lightBg, item.color);
    slide.addShape("roundRect", { x: 0.7, y: y + 0.15, w: 0.5, h: 0.35, fill: { color: item.color }, rectRadius: 0.05 });
    slide.addText(String(i + 1), { x: 0.7, y: y + 0.15, w: 0.5, h: 0.35, fontSize: 14, fontFace: "Arial", bold: true, color: C.textLight, align: "center", valign: "middle" });
    slide.addText(item.action, { x: 1.3, y: y + 0.08, w: 2.5, h: 0.35, fontSize: 12, fontFace: "Microsoft YaHei", bold: true, color: item.color, valign: "middle" });
    slide.addText(item.desc, { x: 1.3, y: y + 0.42, w: 3.0, h: 0.35, fontSize: 9, fontFace: "Microsoft YaHei", color: C.textDark, valign: "middle" });
    slide.addText([
      { text: '效果：', options: { bold: true, color: C.success, fontSize: 9 } },
      { text: item.impact, options: { color: C.textDark, fontSize: 9 } },
    ], { x: 4.5, y: y + 0.08, w: 4.0, h: 0.7, fontFace: "Microsoft YaHei", valign: "middle" });
    slide.addText('负责人：' + item.owner, { x: 8.6, y: y + 0.25, w: 3.8, h: 0.35, fontSize: 9, fontFace: "Microsoft YaHei", color: C.midGray, align: "right", valign: "middle" });
  });

  slide.addText('中期推进（1-2 月）', { x: 0.6, y: 4.4, w: 12, h: 0.35, fontSize: 14, fontFace: "Microsoft YaHei", bold: true, color: C.accent2 });
  slide.addShape("rect", { x: 0.6, y: 4.72, w: 2.5, h: 0.03, fill: { color: C.accent2 } });

  const midterm = [
    { action: '项目语义匹配 + 会话记忆', desc: '简称也能匹配项目，记住用户偏好', impact: '追问减少 60%+' },
    { action: 'ReAct 多步工具编排', desc: '自动组合多个工具，分步完成复杂查询', impact: '能回答的综合问题数翻倍' },
    { action: '追问循环机制', desc: '不确定时主动追问用户，而不是随便猜', impact: '误判和无效回答大幅减少' },
    { action: '前端富文本渲染', desc: '表格、卡片、图表替代纯文本', impact: '信息一目了然' },
  ];

  midterm.forEach((item, i) => {
    const y = 4.85 + i * 0.5;
    slide.addShape("ellipse", { x: 0.7, y: y + 0.1, w: 0.1, h: 0.1, fill: { color: C.accent2 } });
    slide.addText(item.action, { x: 0.9, y: y, w: 3.5, h: 0.4, fontSize: 10, fontFace: "Microsoft YaHei", bold: true, color: C.textDark, valign: "middle" });
    slide.addText(item.desc, { x: 4.5, y: y, w: 4.0, h: 0.4, fontSize: 9, fontFace: "Microsoft YaHei", color: C.midGray, valign: "middle" });
    slide.addText('→ ' + item.impact, { x: 8.6, y: y, w: 3.8, h: 0.4, fontSize: 9, fontFace: "Microsoft YaHei", bold: true, color: C.success, valign: "middle" });
  });

  addFooter(slide, 7);
}

// ===== SLIDE 8: Investment vs Return =====
{
  const slide = pptx.addSlide();
  addSlideBg(slide);
  addHeaderBar(slide, '投入产出分析', '每阶段做什么、花多少时间、带来什么效果');

  const cols = ['阶段', '投入', '产出', '风险'];
  const colX = [0.6, 2.8, 5.6, 9.4];
  const colW = [2.2, 2.8, 3.8, 2.7];

  cols.forEach((c, i) => {
    slide.addShape("roundRect", { x: colX[i], y: 1.1, w: colW[i], h: 0.4, fill: { color: C.primary }, rectRadius: 0.03 });
    slide.addText(c, { x: colX[i], y: 1.1, w: colW[i], h: 0.4, fontSize: 12, fontFace: "Microsoft YaHei", bold: true, color: C.textLight, align: "center", valign: "middle" });
  });

  const roiData = [
    { phase: '第一阶段\n1-2周', investment: '1 人 x 2 周\n优化 Prompt + 分类逻辑', output: '意图识别准确率提升\n回答有结构、不再是数据堆砌', risk: '低\n只改智能层逻辑，不影响安全边界', color: C.danger },
    { phase: '第二阶段\n1-2月', investment: '1-2 人 x 1-2 月\n重构编排引擎 + 前端升级', output: '能回答综合问题\n追问减少、体验像真人助理\n前端可展示表格/卡片', risk: '中\n需要 Redis + 结构化 Schema\n前端需配合', color: C.accent2 },
    { phase: '第三阶段\n3-6月', investment: '2 人 x 3-6 月\n知识库 + 自动评估 + 主动监控', output: '记住用户偏好\n自动发现新需求\n主动推送风险预警\n回答有行业深度', risk: '较高\n依赖数据积累\n需要标注和训练', color: C.accent },
  ];

  roiData.forEach((row, i) => {
    const y = 1.6 + i * 1.8;
    addCard(slide, colX[0], y, colW[0], 1.7, row.color, null);
    slide.addText(row.phase, { x: colX[0] + 0.1, y: y + 0.2, w: colW[0] - 0.2, h: 1.3, fontSize: 11, fontFace: "Microsoft YaHei", bold: true, color: C.textLight, align: "center", valign: "middle" });
    addCard(slide, colX[1], y, colW[1], 1.7, C.lightBg, C.midGray);
    slide.addText(row.investment, { x: colX[1] + 0.1, y: y + 0.2, w: colW[1] - 0.2, h: 1.3, fontSize: 10, fontFace: "Microsoft YaHei", color: C.textDark, valign: "middle" });
    addCard(slide, colX[2], y, colW[2], 1.7, "F0FFF4", C.success);
    slide.addText(row.output, { x: colX[2] + 0.1, y: y + 0.2, w: colW[2] - 0.2, h: 1.3, fontSize: 10, fontFace: "Microsoft YaHei", color: C.textDark, valign: "middle" });
    addCard(slide, colX[3], y, colW[3], 1.7, C.lightBg, row.color);
    slide.addText(row.risk, { x: colX[3] + 0.1, y: y + 0.2, w: colW[3] - 0.2, h: 1.3, fontSize: 10, fontFace: "Microsoft YaHei", color: C.textDark, valign: "middle" });
  });

  addCard(slide, 0.6, 6.2, 12.1, 0.8, C.dark, null);
  slide.addText([
    { text: '关键决策：', options: { bold: true, color: C.accent2, fontSize: 11 } },
    { text: '  第一阶段投入最小、风险最低、效果最明显——建议优先批准启动', options: { color: C.textLight, fontSize: 11 } },
  ], { x: 0.8, y: 6.25, w: 11.7, h: 0.7, fontFace: "Microsoft YaHei", valign: "middle" });

  addFooter(slide, 8);
}

// ===== SLIDE 9: Key Decisions =====
{
  const slide = pptx.addSlide();
  addSlideBg(slide);
  addHeaderBar(slide, '需要老板确认的决策', '技术选型不影响业务目标，但需要提前对齐方向');

  const decisions = [
    { question: '大模型服务是否只依赖 DeepSeek？', optionA: '继续用 DeepSeek（便宜，但依赖单一供应商）', optionB: '准备多模型备份（OpenAI/Claude 作为降级备选）', recommendation: '短期继续 DeepSeek，中期引入多模型备份', impact: '影响回答质量和系统可用性', color: C.primary },
    { question: '会话记忆存在哪里？', optionA: '由飞书后端透传历史消息（简单，耦合度高）', optionB: '智能层自建 Redis 缓存（独立，需要新增基础设施）', recommendation: '中期自建 Redis，让智能层独立管理对话状态', impact: '影响追问体验和数据安全边界', color: C.accent },
    { question: '结构化回答格式怎么定？', optionA: '纯 JSON 格式（灵活，但前端需要适配）', optionB: '每个技能固定输出模板（标准化，前端渲染简单）', recommendation: '每个技能绑定固定输出模板，兼顾灵活和标准化', impact: '影响前端展示效果和开发工作量', color: C.accent2 },
    { question: '多轮对话是否支持？', optionA: '每次都是全新对话（简单，但体验差）', optionB: '支持 thread 恢复（复杂，但体验好）', recommendation: '中期引入 thread 机制，从简单追问开始', impact: '直接影响用户对话体验', color: C.purple },
  ];

  decisions.forEach((d, i) => {
    const y = 1.1 + i * 1.5;
    addCard(slide, 0.6, y, 12.1, 1.4, C.lightBg, d.color);
    slide.addText(d.question, { x: 0.8, y: y + 0.05, w: 5.0, h: 0.35, fontSize: 12, fontFace: "Microsoft YaHei", bold: true, color: d.color, valign: "middle" });
    slide.addShape("roundRect", { x: 0.8, y: y + 0.4, w: 5.3, h: 0.35, fill: { color: C.lightGray }, rectRadius: 0.04 });
    slide.addText([
      { text: '方案A：', options: { bold: true, color: C.midGray, fontSize: 9 } },
      { text: d.optionA, options: { color: C.textDark, fontSize: 9 } },
    ], { x: 0.9, y: y + 0.4, w: 5.1, h: 0.35, fontFace: "Microsoft YaHei", valign: "middle" });
    slide.addShape("roundRect", { x: 0.8, y: y + 0.78, w: 5.3, h: 0.35, fill: { color: C.lightGray }, rectRadius: 0.04 });
    slide.addText([
      { text: '方案B：', options: { bold: true, color: C.midGray, fontSize: 9 } },
      { text: d.optionB, options: { color: C.textDark, fontSize: 9 } },
    ], { x: 0.9, y: y + 0.78, w: 5.1, h: 0.35, fontFace: "Microsoft YaHei", valign: "middle" });
    slide.addText([
      { text: '建议：', options: { bold: true, color: C.success, fontSize: 10 } },
      { text: d.recommendation, options: { color: C.textDark, fontSize: 10 } },
    ], { x: 6.3, y: y + 0.05, w: 6.2, h: 0.45, fontFace: "Microsoft YaHei", valign: "middle" });
    slide.addText([
      { text: '业务影响：', options: { bold: true, color: C.accent2, fontSize: 9 } },
      { text: d.impact, options: { color: C.textDark, fontSize: 9 } },
    ], { x: 6.3, y: y + 0.55, w: 6.2, h: 0.35, fontFace: "Microsoft YaHei", valign: "middle" });
  });

  addFooter(slide, 9);
}

// ===== SLIDE 10: Summary & Call to Action =====
{
  const slide = pptx.addSlide();
  addSlideBg(slide, C.dark);
  slide.addShape("rect", { x: 0, y: 0, w: 13.33, h: 0.12, fill: { color: C.accent } });
  slide.addShape("rect", { x: 0, y: 7.38, w: 13.33, h: 0.12, fill: { color: C.accent } });
  slide.addShape("ellipse", { x: -1, y: -1, w: 3, h: 3, fill: { color: C.primary, transparency: 85 } });
  slide.addShape("ellipse", { x: 11, y: 5, w: 3, h: 3, fill: { color: C.accent, transparency: 85 } });

  slide.addText('总结', { x: 0.8, y: 0.5, w: 10, h: 0.6, fontSize: 32, fontFace: "Microsoft YaHei", bold: true, color: C.textLight });
  slide.addShape("rect", { x: 0.8, y: 1.0, w: 3, h: 0.04, fill: { color: C.accent2 } });

  const summaries = [
    { title: '我们已经搭建了飞书智能助手的基本框架', text: '5 个技能覆盖项目管理的常见场景——施工日报、项目状态、任务风险、周报生成、通用兜底。用户一句话就能查到数据。', color: C.accent },
    { title: '目前最大的短板是「不够聪明」', text: '听不懂换说法、找不准简称、干不了复杂查询、说不出深度分析、记不住用户偏好。这些都是规则驱动的固有限制。', color: C.accent2 },
    { title: '升级方向明确：从「按规则干活」到「能推理会记忆」', text: '三个阶段：先让它听得懂、说得清（1-2周），再让它找得准、干得多（1-2月），最后让它会记忆、能预判（3-6月）。', color: C.success },
    { title: '第一阶段投入最小、效果最明显', text: '只改智能层逻辑，不影响安全边界；投入 1 人 2 周，意图识别准确率和回答质量立刻提升。建议优先批准。', color: C.primary },
  ];

  summaries.forEach((s, i) => {
    const y = 1.2 + i * 1.2;
    slide.addShape("roundRect", { x: 0.6, y, w: 12.1, h: 1.1, fill: { color: C.dark, transparency: 50 }, line: { color: s.color, width: 1.5 }, rectRadius: 0.08 });
    slide.addShape("rect", { x: 0.6, y, w: 0.08, h: 1.1, fill: { color: s.color } });
    slide.addText(s.title, { x: 0.85, y: y + 0.1, w: 11.6, h: 0.3, fontSize: 13, fontFace: "Microsoft YaHei", bold: true, color: s.color, valign: "middle" });
    slide.addText(s.text, { x: 0.85, y: y + 0.42, w: 11.6, h: 0.55, fontSize: 11, fontFace: "Microsoft YaHei", color: C.textLight, valign: "middle" });
  });

  slide.addShape("roundRect", { x: 0.6, y: 6.15, w: 12.1, h: 0.75, fill: { color: C.accent2, transparency: 70 }, line: { color: C.accent2, width: 1.5 }, rectRadius: 0.06 });
  slide.addText([
    { text: '请老板确认：', options: { bold: true, color: C.accent2, fontSize: 14 } },
    { text: '  ① 是否批准第一阶段启动？ ② 4 个技术决策的方向是否认可？ ③ 第二阶段的 Redis 等基础设施是否允许引入？', options: { color: C.textLight, fontSize: 11 } },
  ], { x: 0.8, y: 6.2, w: 11.7, h: 0.65, fontFace: "Microsoft YaHei", valign: "middle" });

  addFooter(slide, 10);
}

// ===== Save =====
const outputPath = path.join(__dirname, 'Lark_Connect_能力盘点与升级路线.pptx');
pptx.writeFile({ fileName: outputPath }).then(() => {
  console.log('PPT generated: ' + outputPath);
}).catch(err => {
  console.error('Error:', err);
  process.exit(1);
});
