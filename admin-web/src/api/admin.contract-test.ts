import { adminApi, type FeishuCardPreset, type ChainTraceResponse, type TraceEventDetail,
  type PromptGovernanceData, type PromptReplayResult } from './admin'

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
