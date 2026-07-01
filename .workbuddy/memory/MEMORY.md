# Lark Connect 项目长期记忆

## 项目概况
- 名称：Lark Connect Agent Gateway（飞书 + Primelayer MCP + DeepSeek）
- 定位：Agent Gateway MVP，后续演进通用 Agent 平台
- 三模块：backend(Java17/SpringBoot3.3.7 安全边界)、agent-service(Python FastAPI+LangGraph 智能层)、admin-web(Vue3+AntDesignVue4+Vite6)
- 身份链路：feishu_open_id → primelayer_user_id → project_id → mcp_token
- 安全约束：MCP Token 永不传入 agent-service；Java 后端是唯一安全边界

## 技术栈要点
- backend：spring-boot-starter(web/amqp/jdbc/security/validation) + larksuite oapi-sdk 2.4.0 + flyway + mysql8
- agent-service：FastAPI 0.115 + LangChain 0.3 + LangGraph 0.2，5步状态机(classify_skill→select_project_scope→plan_tool_calls→summarize_answer→quality_check)，4个技能(项目状态/任务风险/日报周报/通用兜底)，关键词匹配分类
- admin-web：10视图，3通用组件(CrudPage/ReadonlyTable/ResultViewer)
- 基础设施：MySQL8 + Flyway(V1-V5, 9表) + RabbitMQ 异步

## 已知工程问题(待修)
- pom.xml maven-compiler-plugin 误配 source/target=5
- MCP Token AES-GCM 主密钥存 system_config 表，上生产前迁 KMS/环境变量
- 默认凭据 admin/admin123
- DeepSeek 模型名配置不一致(env vs yml)
- 测试覆盖低，核心编排/MCP 适配无测试

## 运行
- 后端：cd backend && mvn spring-boot:run (端口8080)
- agent-service：cd agent-service，python3.10+ venv，uvicorn app.main:app --port 8090
- 前端：cd admin-web && npm install && npm run dev (端口5173)
- 依赖：MySQL8 + RabbitMQ + .env(MYSQL_PASSWORD/DEEPSEEK_API_KEY/飞书凭证)

## 方法论一致性方案（2026-07-01 设计）
- 核心发现：前3步(分类/范围/工具规划)已是确定性逻辑，唯一不确定性来源是 summarize_answer 步骤的 DeepSeek 调用(temperature=0.1)
- 7层保障：①确定性流水线优先 ②temperature=0+固定Seed ③结构化答案模板 ④查询缓存层(Redis, TTL=300s) ⑤Prompt版本管理 ⑥Golden Test回归套件(20+用例) ⑦完整审计可回放
- 技能体系已扩展为5个：project_report(项目报告与施工情况) + project_status_qa + task_risk_qa + weekly_report + general_mcp_qa(兜底)
- project_workflow.py 实现了多轮工具编排：get_base_form_info → match_form_resource → query_form_data_list → batch_get_form_value_detail，支持异步报告轮询

## PPT生成
- 生成脚本：scripts/gen_ppt.js（Node.js + pptxgenjs）
- 运行方式：NODE_PATH=/Users/majiayi/.workbuddy/binaries/node/workspace/node_modules node scripts/gen_ppt.js
- 输出：scripts/Lark_Connect_项目分析与技术路线.pptx (14页)
