import { adminApi, type FeishuCardPreset, type ChainTraceResponse, type TraceEventDetail,
  type PromptGovernanceData, type PromptReplayResult } from './admin'
import type { TerminateTaskResponse } from './admin'
import type { ProjectTokenRequest, ProjectTokenVerifyRequest, UserBindingRequest } from './admin'

const loadPresets: () => Promise<FeishuCardPreset[]> = adminApi.debugFeishuCardPresets

void loadPresets

const loadTrace: (requestId: string) => Promise<ChainTraceResponse> = adminApi.getChainTrace
const loadTraceEvent: (requestId: string, eventId: string) => Promise<TraceEventDetail> = adminApi.getChainTraceEvent

void loadTrace
void loadTraceEvent

const loadPrompts: () => Promise<PromptGovernanceData> = adminApi.getPromptTemplates
const replayPrompt: (id: number, snapshotId: number) => Promise<PromptReplayResult> = adminApi.replayPromptVersion

void loadPrompts
void replayPrompt

const terminateTask: (requestId: string) => Promise<TerminateTaskResponse> = adminApi.terminateFeishuMessage
void terminateTask

const saveUser: (payload: UserBindingRequest) => Promise<unknown> = adminApi.saveUserBinding
const verifyToken: (payload: ProjectTokenVerifyRequest) => Promise<Record<string, unknown>> = adminApi.verifyProjectToken
const saveToken: (payload: ProjectTokenRequest) => Promise<unknown> = adminApi.saveProjectToken
void saveUser
void verifyToken
void saveToken
