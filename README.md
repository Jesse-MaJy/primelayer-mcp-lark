# Lark Connect Agent Gateway

DeepSeek + Primelayer MCP + 飞书 Agent Gateway MVP。

## Modules

- `backend`: Java 17 / Spring Boot API, RabbitMQ worker, MySQL schema, DeepSeek planner, MCP Streamable HTTP adapter.
- `admin-web`: Vue 3 + Ant Design Vue admin console.

## Local Run

1. Start MySQL 8 and RabbitMQ.
2. Create database `lark_connect_agent`.
3. Start backend:

```bash
cd backend
mvn spring-boot:run
```

4. Start admin console:

```bash
cd admin-web
npm install
npm run dev
```

Default admin account is configured in `backend/src/main/resources/application.yml`:

- username: `admin`
- password: `admin123`

## Security Notes

The MVP stores the MCP token AES-GCM master key in `system_config` because this was selected for phase one. This keeps token values encrypted at rest, but it is not production-grade key isolation. Move the master key to environment variables or KMS before production.
