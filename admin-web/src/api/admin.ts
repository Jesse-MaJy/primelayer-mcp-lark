import { http } from './http'

export interface LoginResponse {
  token: string
  expiresInSeconds: number
}

export interface AiSettings {
  deepSeekModel: 'deepseek-v4-pro' | 'deepseek-v4-flash'
  supportedModels: string[]
}

export const adminApi = {
  login: (payload: { username: string; password: string }) =>
    http.post<unknown, LoginResponse>('/api/admin/login', payload),
  listUserBindings: () => http.get<unknown, Record<string, unknown>[]>('/api/admin/user-bindings'),
  saveUserBinding: (payload: Record<string, unknown>) => http.post('/api/admin/user-bindings', payload),
  listProjectTokens: () => http.get<unknown, Record<string, unknown>[]>('/api/admin/project-tokens'),
  verifyProjectToken: (payload: Record<string, unknown>) =>
    http.post<unknown, Record<string, unknown>>('/api/admin/project-tokens/verify', payload),
  saveProjectToken: (payload: Record<string, unknown>) => http.post('/api/admin/project-tokens', payload),
  listChatBindings: () => http.get<unknown, Record<string, unknown>[]>('/api/admin/chat-project-bindings'),
  saveChatBinding: (payload: Record<string, unknown>) => http.post('/api/admin/chat-project-bindings', payload),
  listAuditLogs: () => http.get<unknown, Record<string, unknown>[]>('/api/admin/audit-logs'),
  listAgentTasks: () => http.get<unknown, Record<string, unknown>[]>('/api/admin/agent-tasks'),
  listFeishuMessages: () => http.get<unknown, Record<string, unknown>[]>('/api/admin/feishu-messages'),
  getAiSettings: () => http.get<unknown, AiSettings>('/api/admin/ai-settings'),
  saveAiSettings: (payload: Record<string, unknown>) => http.put<unknown, AiSettings>('/api/admin/ai-settings', payload),
  debugHealth: () => http.get<unknown, Record<string, unknown>>('/api/admin/debug/health'),
  debugDeepSeekConnection: (payload: Record<string, unknown>) =>
    http.post<unknown, Record<string, unknown>>('/api/admin/debug/deepseek/connection', payload),
  debugMcpCall: (payload: Record<string, unknown>) => http.post('/api/admin/debug/mcp/call', payload),
  debugMcpTools: (payload: Record<string, unknown>) =>
    http.post<unknown, Record<string, unknown>>('/api/admin/debug/mcp/tools', payload),
  debugFeishuToken: () => http.get<unknown, Record<string, unknown>>('/api/admin/debug/feishu/token'),
  debugFeishuMockEvent: (payload: Record<string, unknown>) => http.post('/api/admin/debug/feishu/mock-event', payload),
  debugFeishuCard: (payload: Record<string, unknown>) =>
    http.post<unknown, Record<string, unknown>>('/api/admin/debug/feishu/card', payload),
  debugFeishuCardBatch: (payload: Record<string, unknown>) =>
    http.post<unknown, Record<string, unknown>>('/api/admin/debug/feishu/card-batch', payload),
  debugAgentQuery: (payload: Record<string, unknown>) => http.post('/api/admin/debug/agent/query', payload),
  getChainTrace: (requestId: string) =>
    http.get<unknown, Record<string, unknown>>(`/api/admin/chain-trace/${requestId}`)
}
