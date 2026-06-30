# Lark Connect Agent Gateway

DeepSeek + Primelayer MCP + 飞书 Agent Gateway MVP。

The gateway now supports an optional Python Agent Service (`agent-service`) for skill routing, MCP tool planning, and answer summarization. Java remains the security boundary for Feishu events, project permissions, MCP token handling, MCP calls, and audit logs.

## Modules

- `backend`: Java 17 / Spring Boot API, RabbitMQ worker, MySQL schema, DeepSeek planner, MCP Streamable HTTP adapter.
- `agent-service`: Python FastAPI + LangChain/LangGraph service for business skill routing and MCP answer planning.
- `admin-web`: Vue 3 + Ant Design Vue admin console.

## Local Run

1. Start MySQL 8 and RabbitMQ.
2. Create database `lark_connect_agent`.
3. Copy `.env.example` to `.env`, then fill local secrets such as `MYSQL_PASSWORD`, `DEEPSEEK_API_KEY`, and Feishu credentials. The backend also supports `backend/src/main/resources/.env` for packaging-time deployment configuration.
4. Start backend:

```bash
cd backend
mvn spring-boot:run
```

5. Start admin console:

```bash
cd admin-web
npm install
npm run dev
```

Default admin account is configured through `.env`:

- username: `admin`
- password: `admin123`

## Security Notes

The MVP stores the MCP token AES-GCM master key in `system_config` because this was selected for phase one. This keeps token values encrypted at rest, but it is not production-grade key isolation. Move the master key to environment variables or KMS before production.

Spring Boot loads env files in this order:

1. `backend/src/main/resources/.env` packaged in the app.
2. `.env` in the backend working directory.
3. `../.env` in the project root when starting from `backend`.

Later files override earlier files, so production can still mount an external `.env` beside the jar without rebuilding it.
