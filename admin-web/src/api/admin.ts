import { http } from './http'

export interface LoginResponse {
  token: string
  expiresInSeconds: number
}

export interface AiSettings {
  deepSeekModel: 'deepseek-v4-pro' | 'deepseek-v4-flash'
  supportedModels: string[]
}

export type PromptStage = 'PLANNING' | 'FORM_ANALYSIS' | 'FINAL_SUMMARY' | 'PRESENTATION'
export type PromptDomain = 'GLOBAL' | 'SAFETY' | 'QUALITY' | 'PROGRESS' | 'RISK'

export interface PromptVersion {
  id: number
  stage: PromptStage
  domain: PromptDomain
  versionNo: number
  content: string
  status: 'DRAFT' | 'PUBLISHED' | 'ARCHIVED'
  checksum: string
  createdBy: string
  createdAt: string
  publishedAt?: string | null
  active: boolean
}

export interface PromptReplaySnapshot {
  id: number
  requestId: string
  stage: PromptStage
  domain: PromptDomain
  formId?: string | null
  formName?: string | null
  chunkIndex: number
  chunkCount: number
  recordCount: number
  inputChars: number
  createdAt: string
}

export interface PromptGovernanceData {
  versions: PromptVersion[]
  snapshots: PromptReplaySnapshot[]
  allowedVariables: string[]
  securityWarning: string
}

export interface PromptReplayResult {
  snapshotId: number
  candidateVersionId: number
  domain: PromptDomain
  currentOutput: string
  candidateOutput: string
  currentTokens: number
  candidateTokens: number
}

export interface AnswerFeedbackDetail {
  feishuOpenId: string
  personName?: string | null
  rating: 'HELPFUL' | 'PROBLEM'
  reasonCode?: string | null
  reasonLabel?: string | null
  detail?: string | null
  updatedAt: string
}

export interface FeishuCardPreset {
  key: string
  label: string
  description: string
  color: string
  card: Record<string, unknown>
}

export interface TraceNodeSummary {
  id: string
  eventId?: string
  sequence?: number
  type: 'model_call' | 'mcp_call' | string
  purpose?: string | null
  label: string
  status: string
  latencyMs?: number
  inputTokens?: number
  outputTokens?: number
  totalTokens?: number
  returnedCount?: number | null
  reportedTotalCount?: number | null
  cumulativeFetchedCount?: number | null
  coveragePercent?: number | null
  duplicatePage?: boolean
  projectName?: string | null
  toolName?: string | null
  formId?: string | null
  formName?: string | null
  logicalCallId?: string | null
  [key: string]: unknown
}

export interface ChainTraceResponse {
  requestId: string
  legacy: boolean
  completeness: 'COMPLETE' | 'PARTIAL' | 'SUMMARY_ONLY' | string
  traceCompleteness?: 'COMPLETE' | 'PARTIAL' | 'SUMMARY_ONLY' | string
  executionStatus?: 'RUNNING' | 'COMPLETED' | 'PARTIAL' | 'FAILED' | string
  summary: Record<string, any>
  nodes: TraceNodeSummary[]
  edges: Array<{ from: string; to: string }>
}

export interface TraceEventDetail extends TraceNodeSummary {
  input?: unknown
  output?: unknown
  usage?: unknown
  error?: unknown
  metadata?: unknown
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
  listMessageFeedback: (requestId: string) =>
    http.get<unknown, AnswerFeedbackDetail[]>(`/api/admin/feishu-messages/${encodeURIComponent(requestId)}/feedback`),
  getAiSettings: () => http.get<unknown, AiSettings>('/api/admin/ai-settings'),
  saveAiSettings: (payload: Record<string, unknown>) => http.put<unknown, AiSettings>('/api/admin/ai-settings', payload),
  getPromptTemplates: () => http.get<unknown, PromptGovernanceData>('/api/admin/prompt-templates'),
  createPromptVersion: (stage: PromptStage, domain: PromptDomain, content: string) =>
    http.post<unknown, PromptVersion>(`/api/admin/prompt-templates/${stage}/${domain}/versions`, { content }),
  publishPromptVersion: (id: number, note?: string) =>
    http.post<unknown, PromptVersion>(`/api/admin/prompt-templates/versions/${id}/publish`, { note }),
  rollbackPromptVersion: (id: number, note?: string) =>
    http.post<unknown, PromptVersion>(`/api/admin/prompt-templates/versions/${id}/rollback`, { note }),
  replayPromptVersion: (id: number, snapshotId: number) =>
    http.post<unknown, PromptReplayResult>(`/api/admin/prompt-templates/versions/${id}/replay`, { snapshotId }),
  deletePromptReplaySnapshot: (id: number) => http.delete(`/api/admin/prompt-replay-snapshots/${id}`),
  debugHealth: () => http.get<unknown, Record<string, unknown>>('/api/admin/debug/health'),
  debugDeepSeekConnection: (payload: Record<string, unknown>) =>
    http.post<unknown, Record<string, unknown>>('/api/admin/debug/deepseek/connection', payload),
  debugMcpCall: (payload: Record<string, unknown>) => http.post('/api/admin/debug/mcp/call', payload),
  debugMcpTools: (payload: Record<string, unknown>) =>
    http.post<unknown, Record<string, unknown>>('/api/admin/debug/mcp/tools', payload),
  debugFeishuToken: () => http.get<unknown, Record<string, unknown>>('/api/admin/debug/feishu/token'),
  debugFeishuCardPresets: () =>
    http.get<unknown, FeishuCardPreset[]>('/api/admin/debug/feishu/card-presets'),
  debugFeishuMockEvent: (payload: Record<string, unknown>) => http.post('/api/admin/debug/feishu/mock-event', payload),
  debugFeishuCard: (payload: Record<string, unknown>) =>
    http.post<unknown, Record<string, unknown>>('/api/admin/debug/feishu/card', payload),
  debugFeishuCardBatch: (payload: Record<string, unknown>) =>
    http.post<unknown, Record<string, unknown>>('/api/admin/debug/feishu/card-batch', payload),
  debugAgentQuery: (payload: Record<string, unknown>) => http.post('/api/admin/debug/agent/query', payload),
  getChainTrace: (requestId: string) =>
    http.get<unknown, ChainTraceResponse>(`/api/admin/chain-trace/${encodeURIComponent(requestId)}`),
  getChainTraceEvent: (requestId: string, eventId: string) =>
    http.get<unknown, TraceEventDetail>(
      `/api/admin/chain-trace/${encodeURIComponent(requestId)}/events/${encodeURIComponent(eventId)}`
    )
}
