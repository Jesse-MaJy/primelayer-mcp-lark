<template>
  <section class="page prompt-page">
    <div class="page-header">
      <div>
        <h1 class="page-title">提示词治理</h1>
        <div class="page-subtitle">按阶段与业务域管理不可变版本，发布前使用真实脱敏分块进行回放对比。</div>
      </div>
      <a-button :loading="loading" @click="load">刷新</a-button>
    </div>

    <a-alert v-if="data?.securityWarning" type="warning" show-icon :message="data.securityWarning" class="security-alert" />

    <div class="scope-bar">
      <a-segmented v-model:value="stage" :options="stageOptions" />
      <a-select v-model:value="domain" :options="domainOptions" class="domain-select" />
      <a-tag color="blue">生效版本：{{ activeVersion ? `v${activeVersion.versionNo}` : '内置默认' }}</a-tag>
    </div>

    <a-spin :spinning="loading">
      <div class="governance-grid">
        <a-card title="版本时间线" size="small" class="version-card">
          <a-empty v-if="filteredVersions.length === 0" description="该范围尚无数据库版本" />
          <button v-for="version in filteredVersions" :key="version.id" type="button"
                  :class="['version-row', { selected: selected?.id === version.id }]"
                  @click="selectVersion(version)">
            <span>
              <strong>v{{ version.versionNo }}</strong>
              <small>{{ formatTime(version.createdAt) }} · {{ version.createdBy }}</small>
            </span>
            <a-tag :color="statusColor(version)">{{ statusLabel(version) }}</a-tag>
          </button>
        </a-card>

        <div class="editor-stack">
          <a-card size="small" title="模板编辑">
            <template #extra>
              <a-space>
                <a-button @click="resetDraft">重置</a-button>
                <a-button type="primary" :loading="saving" @click="createDraft">保存为新草稿</a-button>
              </a-space>
            </template>
            <div class="variable-hint">
              <span>允许变量</span>
              <a-tag v-for="variable in data?.allowedVariables || []" :key="variable">{{ variableToken(variable) }}</a-tag>
            </div>
            <a-textarea v-model:value="draftContent" :rows="16" show-count :maxlength="50000"
                        placeholder="输入业务模板；平台权限、日期和脱敏规则不可在此修改。" />
            <div class="editor-actions">
              <a-input v-model:value="actionNote" placeholder="发布或回滚备注（可选）" />
              <a-button v-if="selected?.status === 'DRAFT'" type="primary" :loading="acting" @click="publish">发布</a-button>
              <a-button v-if="selected && !selected.active && selected.status !== 'DRAFT'" danger :loading="acting" @click="rollback">回滚到此版本</a-button>
            </div>
          </a-card>

          <a-card size="small" title="与当前生效版本对比">
            <div class="diff-grid">
              <div><h3>当前生效</h3><pre>{{ activeVersion?.content || '使用代码内置默认模板' }}</pre></div>
              <div><h3>候选内容</h3><pre>{{ draftContent || '—' }}</pre></div>
            </div>
          </a-card>

          <a-card size="small" title="历史数据回放">
            <div class="replay-toolbar">
              <a-select v-model:value="snapshotId" show-search option-filter-prop="label"
                        :options="snapshotOptions" placeholder="选择新链路产生的脱敏分块快照" />
              <a-button type="primary" :disabled="!selected || !snapshotId" :loading="replaying" @click="replay">对比运行</a-button>
              <a-button danger :disabled="!snapshotId" @click="deleteSnapshot">删除快照</a-button>
            </div>
            <a-empty v-if="!replayResult" description="选择 FORM_ANALYSIS 版本与快照后运行" />
            <div v-else class="diff-grid replay-output">
              <div><h3>当前版本 · {{ replayResult.currentTokens }} Token</h3><pre>{{ replayResult.currentOutput }}</pre></div>
              <div><h3>候选版本 · {{ replayResult.candidateTokens }} Token</h3><pre>{{ replayResult.candidateOutput }}</pre></div>
            </div>
          </a-card>
        </div>
      </div>
    </a-spin>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, watch } from 'vue'
import { message, Modal } from 'ant-design-vue'
import { adminApi, type PromptDomain, type PromptGovernanceData, type PromptReplayResult,
  type PromptStage, type PromptVersion } from '../api/admin'
import { formatChinaDateTime } from '../utils/time'

const stage = ref<PromptStage>('PLANNING')
const domain = ref<PromptDomain>('GLOBAL')
const data = ref<PromptGovernanceData>()
const loading = ref(false)
const saving = ref(false)
const acting = ref(false)
const replaying = ref(false)
const selected = ref<PromptVersion>()
const draftContent = ref('')
const actionNote = ref('')
const snapshotId = ref<number>()
const replayResult = ref<PromptReplayResult>()

const stageOptions = [
  { label: '规划', value: 'PLANNING' }, { label: '分块分析', value: 'FORM_ANALYSIS' },
  { label: '最终汇总', value: 'FINAL_SUMMARY' }, { label: '展示格式化', value: 'PRESENTATION' }
]
const domainOptions = [
  { label: '全局', value: 'GLOBAL' }, { label: '安全', value: 'SAFETY' },
  { label: '质量', value: 'QUALITY' }, { label: '进度', value: 'PROGRESS' }, { label: '风险', value: 'RISK' }
]
const filteredVersions = computed(() => (data.value?.versions || []).filter(item =>
  item.stage === stage.value && item.domain === domain.value))
const activeVersion = computed(() => filteredVersions.value.find(item => item.active))
const snapshotOptions = computed(() => (data.value?.snapshots || []).filter(item =>
  item.stage === 'FORM_ANALYSIS' && (domain.value === 'GLOBAL' || item.domain === domain.value)).map(item => ({
    value: item.id,
    label: `${item.requestId} · ${item.formName || item.formId || '表单'} · ${item.chunkIndex}/${item.chunkCount} · ${item.recordCount} 条`
  })))

watch([stage, domain], () => {
  selected.value = filteredVersions.value[0]
  draftContent.value = selected.value?.content || ''
  replayResult.value = undefined
  snapshotId.value = undefined
})

async function load() {
  loading.value = true
  try {
    data.value = await adminApi.getPromptTemplates()
    const current = filteredVersions.value.find(item => item.id === selected.value?.id) || filteredVersions.value[0]
    selected.value = current
    draftContent.value = current?.content || ''
  } catch (error) { showError(error) } finally { loading.value = false }
}

function selectVersion(version: PromptVersion) {
  selected.value = version
  draftContent.value = version.content
  replayResult.value = undefined
}

function resetDraft() { draftContent.value = selected.value?.content || '' }

async function createDraft() {
  saving.value = true
  try {
    const version = await adminApi.createPromptVersion(stage.value, domain.value, draftContent.value)
    message.success(`草稿 v${version.versionNo} 已保存`)
    await load()
    selectVersion((data.value?.versions || []).find(item => item.id === version.id) || version)
  } catch (error) { showError(error) } finally { saving.value = false }
}

async function publish() {
  if (!selected.value) return
  acting.value = true
  try { await adminApi.publishPromptVersion(selected.value.id, actionNote.value); message.success('版本已发布'); await load() }
  catch (error) { showError(error) } finally { acting.value = false }
}

async function rollback() {
  if (!selected.value) return
  acting.value = true
  try { await adminApi.rollbackPromptVersion(selected.value.id, actionNote.value); message.success('已切换到所选历史版本'); await load() }
  catch (error) { showError(error) } finally { acting.value = false }
}

async function replay() {
  if (!selected.value || !snapshotId.value) return
  replaying.value = true
  try { replayResult.value = await adminApi.replayPromptVersion(selected.value.id, snapshotId.value) }
  catch (error) { showError(error) } finally { replaying.value = false }
}

function deleteSnapshot() {
  if (!snapshotId.value) return
  Modal.confirm({ title: '删除回放快照？', content: '删除后无法恢复，但不会影响原始查询记录。', okType: 'danger',
    async onOk() { await adminApi.deletePromptReplaySnapshot(snapshotId.value!); snapshotId.value = undefined; replayResult.value = undefined; await load() } })
}

function statusColor(version: PromptVersion) { return version.active ? 'green' : version.status === 'DRAFT' ? 'blue' : 'default' }
function statusLabel(version: PromptVersion) { return version.active ? '生效中' : version.status === 'DRAFT' ? '草稿' : '历史' }
function formatTime(value: string) { return formatChinaDateTime(value) }
function variableToken(value: string) { return `{{${value}}}` }
function showError(error: unknown) { message.error(error instanceof Error ? error.message : '操作失败') }

onMounted(load)
</script>

<style scoped>
.page-subtitle { margin-top: 6px; color: #5b667a; font-size: 13px; }
.security-alert { margin-bottom: 14px; }
.scope-bar { display: flex; gap: 12px; align-items: center; margin-bottom: 14px; padding: 12px; background: #fff; border: 1px solid #dbeafe; border-radius: 8px; }
.domain-select { width: 150px; }
.governance-grid { display: grid; grid-template-columns: 280px minmax(0, 1fr); gap: 14px; align-items: start; }
.version-card { position: sticky; top: 12px; }
.version-row { width: 100%; display: flex; align-items: center; justify-content: space-between; gap: 8px; padding: 10px; border: 0; border-bottom: 1px solid #eef1f6; background: transparent; text-align: left; cursor: pointer; transition: background 180ms ease; }
.version-row:hover, .version-row.selected { background: #e9eef6; }
.version-row:focus-visible { outline: 2px solid #1e40af; outline-offset: -2px; }
.version-row span { display: grid; gap: 3px; }
.version-row small { color: #687386; }
.editor-stack { display: grid; gap: 14px; min-width: 0; }
.variable-hint { display: flex; flex-wrap: wrap; align-items: center; gap: 6px; margin-bottom: 10px; color: #5b667a; }
.editor-actions, .replay-toolbar { display: flex; gap: 8px; margin-top: 12px; }
.replay-toolbar :deep(.ant-select) { flex: 1; min-width: 280px; }
.diff-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 12px; }
.diff-grid h3 { margin: 0 0 8px; font-size: 13px; color: #1e3a8a; }
.diff-grid pre { min-height: 120px; max-height: 360px; margin: 0; padding: 12px; overflow: auto; white-space: pre-wrap; overflow-wrap: anywhere; background: #f8fafc; border: 1px solid #dbeafe; border-radius: 6px; font: 12px/1.6 ui-monospace, SFMono-Regular, Menlo, monospace; }
.replay-output { margin-top: 12px; }
@media (max-width: 900px) { .governance-grid, .diff-grid { grid-template-columns: 1fr; } .version-card { position: static; } .scope-bar { align-items: stretch; flex-direction: column; } .domain-select { width: 100%; } .editor-actions, .replay-toolbar { flex-wrap: wrap; } }
@media (prefers-reduced-motion: reduce) { .version-row { transition: none; } }
</style>
