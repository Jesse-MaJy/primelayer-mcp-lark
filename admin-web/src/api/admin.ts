import { http } from './http'

export interface LoginResponse {
  token: string
  expiresInSeconds: number
}

export const adminApi = {
  login: (payload: { username: string; password: string }) =>
    http.post<unknown, LoginResponse>('/api/admin/login', payload),
  listUserBindings: () => http.get<unknown, Record<string, unknown>[]>('/api/admin/user-bindings'),
  saveUserBinding: (payload: Record<string, unknown>) => http.post('/api/admin/user-bindings', payload),
  listProjectTokens: () => http.get<unknown, Record<string, unknown>[]>('/api/admin/project-tokens'),
  saveProjectToken: (payload: Record<string, unknown>) => http.post('/api/admin/project-tokens', payload),
  listChatBindings: () => http.get<unknown, Record<string, unknown>[]>('/api/admin/chat-project-bindings'),
  saveChatBinding: (payload: Record<string, unknown>) => http.post('/api/admin/chat-project-bindings', payload),
  listAuditLogs: () => http.get<unknown, Record<string, unknown>[]>('/api/admin/audit-logs'),
  listAgentTasks: () => http.get<unknown, Record<string, unknown>[]>('/api/admin/agent-tasks'),
  debugHealth: () => http.get<unknown, Record<string, unknown>>('/api/admin/debug/health'),
  debugDeepSeekPlan: (payload: Record<string, unknown>) => http.post('/api/admin/debug/deepseek/plan', payload),
  debugDeepSeekSummarize: (payload: Record<string, unknown>) => http.post('/api/admin/debug/deepseek/summarize', payload),
  debugMcpCall: (payload: Record<string, unknown>) => http.post('/api/admin/debug/mcp/call', payload),
  debugFeishuMockEvent: (payload: Record<string, unknown>) => http.post('/api/admin/debug/feishu/mock-event', payload),
  debugAgentQuery: (payload: Record<string, unknown>) => http.post('/api/admin/debug/agent/query', payload)
}
