<template>
  <a-spin :spinning="loading" style="display: block">
    <a-alert v-if="error" type="error" :message="error" show-icon class="trace-alert" />
    <template v-if="trace">
      <a-alert
        v-if="trace.legacy"
        type="warning"
        show-icon
        message="历史汇总链路"
        description="该记录生成于逐节点追踪上线前，节点级输入、输出、耗时和 Token 无法恢复。"
        class="trace-alert"
      />
      <a-alert
        v-else-if="trace.completeness === 'PARTIAL'"
        type="warning"
        show-icon
        message="追踪数据不完整"
        description="业务回答未受影响，但部分追踪事件写入失败。"
        class="trace-alert"
      />

      <a-card size="small" title="状态语义" class="trace-section">
        <a-descriptions size="small" bordered :column="2">
          <a-descriptions-item label="业务执行状态">
            <a-tag :color="statusColor(trace.executionStatus || trace.summary.executionStatus || 'RUNNING')">
              {{ trace.executionStatus || trace.summary.executionStatus || 'RUNNING' }}
            </a-tag>
          </a-descriptions-item>
          <a-descriptions-item label="追踪完整性">
            <a-tag :color="(trace.traceCompleteness || trace.completeness) === 'COMPLETE' ? 'green' : 'orange'">
              {{ trace.traceCompleteness || trace.completeness }}
            </a-tag>
          </a-descriptions-item>
        </a-descriptions>
      </a-card>

      <div class="metric-grid">
        <div class="metric-card"><span>模型调用</span><strong>{{ metric('modelCallCount', 0) }}</strong></div>
        <div class="metric-card"><span>业务 MCP</span><strong>{{ metric('businessPhysicalCalls', 'totalMcpCalls') }}</strong></div>
        <div class="metric-card"><span>工具发现</span><strong>{{ metric('toolDiscoveryCalls', 0) }}</strong></div>
        <div class="metric-card"><span>累计获取条数</span><strong>{{ formatNumber(metric('returnedCount', 0)) }}</strong></div>
        <div class="metric-card"><span>总 Token</span><strong>{{ formatNumber(metric('totalTokens', tokenFallback)) }}</strong></div>
        <div class="metric-card"><span>缓存命中</span><strong>{{ formatNumber(metric('cacheHitCount', 'cacheHits')) }}</strong></div>
        <div class="metric-card"><span>节省 MCP</span><strong>{{ formatNumber(metric('savedMcpCalls', 0)) }}</strong></div>
        <div class="metric-card"><span>处理耗时</span><strong>{{ formatMs(metric('processingLatencyMs', 'totalLatencyMs')) }}</strong></div>
      </div>

      <a-card v-if="lifecycleVisible" size="small" title="可恢复查询状态" class="trace-section">
        <a-descriptions size="small" bordered :column="4">
          <a-descriptions-item label="当前阶段">{{ trace.summary.phase || '—' }}</a-descriptions-item>
          <a-descriptions-item label="检查点版本">{{ trace.summary.checkpointVersion ?? '—' }}</a-descriptions-item>
          <a-descriptions-item label="异步轮询">{{ formatNumber(trace.summary.pollAttempts || 0) }}</a-descriptions-item>
          <a-descriptions-item label="瞬时重试">{{ formatNumber(trace.summary.retryCount || 0) }}</a-descriptions-item>
          <a-descriptions-item label="重启恢复">{{ trace.summary.recoveredAfterRestart ? '是' : '否' }}</a-descriptions-item>
          <a-descriptions-item label="15 分钟通知">{{ trace.summary.longRunningNoticeSent ? '已发送' : '未发送' }}</a-descriptions-item>
          <a-descriptions-item label="停止原因" :span="2">{{ trace.summary.stopReason || '—' }}</a-descriptions-item>
        </a-descriptions>
      </a-card>

      <a-card v-if="tokenBreakdown.length" size="small" title="Token 用途分类" class="trace-section">
        <a-descriptions size="small" bordered :column="4">
          <a-descriptions-item v-for="item in tokenBreakdown" :key="item.purpose" :label="purposeLabel(item.purpose)">
            {{ formatNumber(item.totalTokens) }}
          </a-descriptions-item>
        </a-descriptions>
      </a-card>

      <a-card size="small" title="真实调用流程" class="trace-section">
        <template v-if="formGroups.length" #extra>
          <a-space size="small">
            <a-button type="link" size="small" @click="setAllFormGroups(true)">展开全部</a-button>
            <a-button type="link" size="small" @click="setAllFormGroups(false)">折叠全部</a-button>
          </a-space>
        </template>
        <div ref="mermaidContainer" class="trace-graph" aria-label="MCP 调用流程图"></div>
      </a-card>

      <a-card size="small" title="节点明细" class="trace-section">
        <div class="trace-filters">
          <a-select v-model:value="typeFilter" allow-clear placeholder="全部类型" style="width: 150px">
            <a-select-option value="model_call">模型调用</a-select-option>
            <a-select-option value="mcp_call">MCP 调用</a-select-option>
          </a-select>
          <a-select v-model:value="statusFilter" allow-clear placeholder="全部状态" style="width: 150px">
            <a-select-option value="SUCCEEDED">成功</a-select-option>
            <a-select-option value="PARTIAL">部分成功</a-select-option>
            <a-select-option value="FAILED">失败</a-select-option>
            <a-select-option value="RUNNING">执行中</a-select-option>
          </a-select>
          <span class="filter-count">{{ filteredNodes.length }} / {{ trace.nodes.length }} 个原始节点</span>
        </div>
        <div class="table-scroll">
          <a-table :columns="columns" :data-source="displayRows" row-key="rowKey" size="small"
                   :pagination="false" :row-class-name="rowClassName">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'label'">
                <span :class="['node-label', { 'node-label-child': record.rowKind === 'child' }]">
                  <span v-if="record.rowKind === 'group'"
                        :class="['group-caret', { expanded: isFormGroupExpanded(record.groupKey) }]"
                        aria-hidden="true"></span>
                  {{ record.label }}
                </span>
              </template>
              <template v-else-if="column.key === 'type'">
                <a-tag :color="record.type === 'model_call' ? 'blue' : 'green'">
                  {{ record.rowKind === 'group' ? `MCP · ${record.nodeCount}` : typeLabel(record.type) }}
                </a-tag>
              </template>
              <template v-else-if="column.key === 'status'">
                <a-tag :color="statusColor(record.status)">{{ statusLabel(record.status) }}</a-tag>
              </template>
              <template v-else-if="column.key === 'latency'">{{ formatMs(record.latencyMs) }}</template>
              <template v-else-if="column.key === 'usage'">
                <span v-if="record.type === 'model_call'">{{ formatNumber(record.totalTokens || 0) }} Token</span>
                <span v-else-if="record.cumulativeFetchedCount != null">
                  累计 {{ formatNumber(record.cumulativeFetchedCount) }}<template v-if="record.reportedTotalCount != null"> / 总计 {{ formatNumber(record.reportedTotalCount) }}</template> 条
                </span>
                <span v-else-if="record.returnedCount != null">本页 {{ formatNumber(record.returnedCount) }} 条</span>
                <span v-else class="muted">总数未知</span>
              </template>
              <template v-else-if="column.key === 'action'">
                <a-button v-if="record.rowKind === 'group'" type="link" size="small"
                          :aria-expanded="isFormGroupExpanded(record.groupKey)"
                          @click="toggleFormGroup(record.groupKey)">
                  {{ isFormGroupExpanded(record.groupKey) ? '收起' : '展开' }}
                </a-button>
                <a-button v-else type="link" size="small" @click="openDetail(record)">查看详情</a-button>
              </template>
            </template>
          </a-table>
        </div>
      </a-card>
    </template>
  </a-spin>

  <a-modal v-model:open="detailOpen" :title="detailTitle" width="900px" :footer="null">
    <a-spin :spinning="detailLoading">
      <a-alert v-if="detailError" type="error" :message="detailError" show-icon class="trace-alert" />
      <a-tabs v-if="detail">
        <a-tab-pane key="input" tab="输入"><pre class="json-block">{{ formatJson(detail.input) }}</pre></a-tab-pane>
        <a-tab-pane key="output" tab="输出"><pre class="json-block">{{ formatJson(detail.output) }}</pre></a-tab-pane>
        <a-tab-pane key="usage" tab="统计">
          <a-descriptions size="small" bordered :column="2">
            <a-descriptions-item label="输入 Token">{{ detail.inputTokens || 0 }}</a-descriptions-item>
            <a-descriptions-item label="输出 Token">{{ detail.outputTokens || 0 }}</a-descriptions-item>
            <a-descriptions-item label="总 Token">{{ detail.totalTokens || 0 }}</a-descriptions-item>
            <a-descriptions-item label="耗时">{{ formatMs(detail.latencyMs) }}</a-descriptions-item>
            <a-descriptions-item label="本次返回条数">{{ detail.returnedCount ?? '未知' }}</a-descriptions-item>
            <a-descriptions-item label="累计获取条数">{{ detail.cumulativeFetchedCount ?? '未知' }}</a-descriptions-item>
            <a-descriptions-item label="服务端总条数">{{ detail.reportedTotalCount ?? '未知' }}</a-descriptions-item>
            <a-descriptions-item label="覆盖率">
              {{ detail.coveragePercent == null ? '未知' : `${Number(detail.coveragePercent).toFixed(1)}%` }}
            </a-descriptions-item>
          </a-descriptions>
          <pre class="json-block usage-block">{{ formatJson(detail.usage) }}</pre>
        </a-tab-pane>
        <a-tab-pane key="meta" tab="错误与元信息">
          <pre class="json-block">{{ formatJson({ error: detail.error, metadata: detail.metadata }) }}</pre>
        </a-tab-pane>
      </a-tabs>
    </a-spin>
  </a-modal>
</template>

<script setup lang="ts">
import { computed, nextTick, ref, watch } from 'vue'
import { adminApi, type ChainTraceResponse, type TraceEventDetail, type TraceNodeSummary } from '../api/admin'

const props = defineProps<{ requestId: string }>()
const loading = ref(false)
const error = ref('')
const trace = ref<ChainTraceResponse | null>(null)
const mermaidContainer = ref<HTMLDivElement>()
const typeFilter = ref<string>()
const statusFilter = ref<string>()
const detailOpen = ref(false)
const detailLoading = ref(false)
const detailError = ref('')
const detail = ref<TraceEventDetail | null>(null)
const renderId = `trace-${Math.random().toString(36).slice(2)}`
let renderVersion = 0
const expandedFormGroups = ref<Set<string>>(new Set())

type DisplayRowKind = 'group' | 'child' | 'node'
type TraceDisplayRow = TraceNodeSummary & {
  rowKey: string
  rowKind: DisplayRowKind
  groupKey?: string
  nodeCount?: number
}

interface FormTraceGroup {
  key: string
  label: string
  nodes: TraceNodeSummary[]
}

const columns = [
  { title: '节点', dataIndex: 'label', key: 'label', ellipsis: true, width: 260 },
  { title: '类型', key: 'type', width: 96 },
  { title: '状态', key: 'status', width: 96 },
  { title: '耗时', key: 'latency', width: 90 },
  { title: 'Token / 数据量', key: 'usage', width: 130 },
  { title: '操作', key: 'action', width: 90 }
]

const filteredNodes = computed(() => (trace.value?.nodes || []).filter(node =>
  (!typeFilter.value || node.type === typeFilter.value) &&
  (!statusFilter.value || node.status === statusFilter.value)
))
const formGroups = computed<FormTraceGroup[]>(() => buildFormGroups(trace.value?.nodes || []))
const displayRows = computed<TraceDisplayRow[]>(() => {
  const rows: TraceDisplayRow[] = []
  const grouped = new Set<string>()
  const groups = new Map(buildFormGroups(filteredNodes.value).map(group => [group.key, group]))
  for (const node of filteredNodes.value) {
    const key = formGroupKey(node)
    if (!key) {
      rows.push({ ...node, rowKey: node.id, rowKind: 'node' })
      continue
    }
    if (grouped.has(key)) continue
    grouped.add(key)
    const group = groups.get(key)
    if (!group) continue
    rows.push(groupDisplayRow(group))
    if (isFormGroupExpanded(key)) {
      rows.push(...group.nodes.map(child => ({
        ...child,
        rowKey: `child:${child.id}`,
        rowKind: 'child' as const,
        groupKey: key
      })))
    }
  }
  return rows
})
const detailTitle = computed(() => detail.value?.label || '节点详情')
const tokenFallback = computed(() => Number(trace.value?.summary?.inputTokens || 0) + Number(trace.value?.summary?.outputTokens || 0))
const lifecycleVisible = computed(() => Boolean(trace.value?.summary?.phase))
const tokenBreakdown = computed(() => Object.entries(trace.value?.summary?.tokenBreakdown || {}).map(([purpose, raw]) => {
  const usage = raw as Record<string, unknown>
  return { purpose, totalTokens: Number(usage.totalTokens || 0) }
}))

watch(() => props.requestId, loadTrace, { immediate: true })
watch(expandedFormGroups, async () => {
  await nextTick()
  await renderGraph()
})

async function loadTrace() {
  if (!props.requestId) return
  loading.value = true
  error.value = ''
  trace.value = null
  expandedFormGroups.value = new Set()
  try {
    trace.value = await adminApi.getChainTrace(props.requestId)
    await nextTick()
    await renderGraph()
  } catch (cause) {
    error.value = cause instanceof Error ? cause.message : '加载链路失败'
  } finally {
    loading.value = false
  }
}

async function renderGraph() {
  if (!trace.value || !mermaidContainer.value) return
  try {
    const mermaid = (await import('mermaid')).default
    mermaid.initialize({ startOnLoad: false, theme: 'default', securityLevel: 'loose' })
    const idMap = new Map<string, string>()
    const graphNodes: Array<{ id: string; label: string; type: string; status: string; group: boolean }> = []
    const emittedGroups = new Set<string>()
    const collapsedGroupIds = new Map<string, string>()
    const groups = new Map(formGroups.value.map(group => [group.key, group]))
    let graphNodeIndex = 0

    trace.value.nodes.forEach(node => {
      const groupKey = formGroupKey(node)
      if (groupKey && !isFormGroupExpanded(groupKey)) {
        const group = groups.get(groupKey)
        if (!group) return
        let graphId = collapsedGroupIds.get(groupKey)
        if (!graphId) {
          graphId = `n${graphNodeIndex++}`
          collapsedGroupIds.set(groupKey, graphId)
        }
        group.nodes.forEach(child => idMap.set(child.id, graphId))
        if (!emittedGroups.has(groupKey)) {
          emittedGroups.add(groupKey)
          graphNodes.push({
            id: graphId,
            label: `${group.label}（${group.nodes.length} 个 MCP 节点）`,
            type: 'mcp_call',
            status: aggregateStatus(group.nodes),
            group: true
          })
        }
        return
      }
      const graphId = `n${graphNodeIndex++}`
      idMap.set(node.id, graphId)
      graphNodes.push({ id: graphId, label: String(node.label || node.id), type: node.type,
        status: node.status, group: false })
    })

    let chart = 'graph TD\n'
    graphNodes.forEach(node => {
      const label = node.label.replace(/[()]/g, '').replace(/"/g, "'")
      chart += `  ${node.id}["${label}"]\n`
      const colors = node.status === 'SUCCEEDED'
        ? node.type === 'model_call' ? ['#e6f4ff', '#1677ff'] : ['#f6ffed', '#52c41a']
        : node.status === 'PARTIAL' ? ['#fffbe6', '#d97706']
          : node.status === 'RUNNING' || node.status === 'PENDING' || node.status === 'RETRYING'
            ? ['#e6f4ff', '#1677ff'] : ['#fff2f0', '#dc2626']
      chart += `  style ${node.id} fill:${colors[0]},stroke:${colors[1]}${node.group ? ',stroke-width:2px' : ''}\n`
    })
    const emittedEdges = new Set<string>()
    trace.value.edges.forEach(edge => {
      const from = idMap.get(edge.from)
      const to = idMap.get(edge.to)
      const key = `${from}->${to}`
      if (from && to && from !== to && !emittedEdges.has(key)) {
        emittedEdges.add(key)
        chart += `  ${from} --> ${to}\n`
      }
    })
    const { svg } = await mermaid.render(`${renderId}-${renderVersion++}`, chart)
    mermaidContainer.value.innerHTML = svg
  } catch (cause) {
    mermaidContainer.value.innerHTML = `<p class="graph-error">流程图渲染失败：${escapeHtml(cause instanceof Error ? cause.message : '未知错误')}</p>`
  }
}

function formGroupKey(node: TraceNodeSummary): string | null {
  if (node.type !== 'mcp_call' || !node.formId) return null
  return `form:${node.projectName || node.projectId || 'unknown'}:${node.formId}`
}

function buildFormGroups(nodes: TraceNodeSummary[]): FormTraceGroup[] {
  const groups = new Map<string, FormTraceGroup>()
  for (const node of nodes) {
    const key = formGroupKey(node)
    if (!key) continue
    const existing = groups.get(key)
    if (existing) {
      existing.nodes.push(node)
      continue
    }
    const formLabel = node.formName && node.formName !== node.formId
      ? node.formName : `表单 ${node.formId}`
    groups.set(key, {
      key,
      label: node.projectName ? `${formLabel} · ${node.projectName}` : formLabel,
      nodes: [node]
    })
  }
  return Array.from(groups.values())
}

function groupDisplayRow(group: FormTraceGroup): TraceDisplayRow {
  const cumulativeFetchedCount = maxNumber(group.nodes.map(node => node.cumulativeFetchedCount))
  const reportedTotalCount = maxNumber(group.nodes.map(node => node.reportedTotalCount))
  const returnedCount = group.nodes.reduce((sum, node) =>
    sum + (node.duplicatePage ? 0 : Number(node.returnedCount || 0)), 0)
  return {
    id: group.key,
    rowKey: `group:${group.key}`,
    rowKind: 'group',
    groupKey: group.key,
    nodeCount: group.nodes.length,
    type: 'mcp_call',
    label: group.label,
    status: aggregateStatus(group.nodes),
    latencyMs: group.nodes.reduce((sum, node) => sum + Number(node.latencyMs || 0), 0),
    returnedCount,
    cumulativeFetchedCount: cumulativeFetchedCount ?? returnedCount,
    reportedTotalCount
  }
}

function maxNumber(values: Array<number | null | undefined>): number | null {
  const numbers = values.filter((value): value is number => value != null).map(Number)
  return numbers.length ? Math.max(...numbers) : null
}

function aggregateStatus(nodes: TraceNodeSummary[]): string {
  const statuses = new Set(nodes.map(node => node.status))
  if (statuses.has('FAILED')) return 'FAILED'
  if (statuses.has('PARTIAL')) return 'PARTIAL'
  if (statuses.has('RUNNING') || statuses.has('PENDING') || statuses.has('RETRYING')) return 'RUNNING'
  return 'SUCCEEDED'
}

function isFormGroupExpanded(groupKey?: string): boolean {
  return Boolean(groupKey && expandedFormGroups.value.has(groupKey))
}

function toggleFormGroup(groupKey?: string) {
  if (!groupKey) return
  const next = new Set(expandedFormGroups.value)
  if (next.has(groupKey)) next.delete(groupKey)
  else next.add(groupKey)
  expandedFormGroups.value = next
}

function setAllFormGroups(expanded: boolean) {
  expandedFormGroups.value = expanded
    ? new Set(formGroups.value.map(group => group.key))
    : new Set()
}

function rowClassName(record: TraceDisplayRow) {
  return record.rowKind === 'group' ? 'form-group-row'
    : record.rowKind === 'child' ? 'form-child-row' : ''
}

async function openDetail(node: TraceNodeSummary) {
  detailOpen.value = true
  detailLoading.value = true
  detailError.value = ''
  detail.value = null
  try {
    if (trace.value?.legacy) {
      detail.value = { ...node, input: node.input || node.request, output: node.output || node.response,
        metadata: node } as TraceEventDetail
    } else {
      detail.value = await adminApi.getChainTraceEvent(props.requestId, node.eventId || node.id)
    }
  } catch (cause) {
    detailError.value = cause instanceof Error ? cause.message : '加载节点详情失败'
  } finally {
    detailLoading.value = false
  }
}

function metric(primary: string, fallback: string | number | { value: number }): number {
  const value = trace.value?.summary?.[primary]
  if (value != null) return Number(value)
  if (typeof fallback === 'string') return Number(trace.value?.summary?.[fallback] || 0)
  if (typeof fallback === 'object') return fallback.value
  return fallback
}
function formatNumber(value: unknown) { return new Intl.NumberFormat('zh-CN').format(Number(value || 0)) }
function formatMs(value: unknown) {
  const ms = Number(value || 0)
  if (!ms) return '-'
  return ms >= 1000 ? `${(ms / 1000).toFixed(1)}s` : `${ms}ms`
}
function formatJson(value: unknown) {
  if (value == null || value === '') return '(无)'
  if (typeof value === 'string') {
    try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value }
  }
  return JSON.stringify(value, null, 2)
}
function typeLabel(type: string) { return type === 'model_call' ? '模型' : type === 'mcp_call' ? 'MCP' : type }
function purposeLabel(purpose: string) {
  return ({ planning: '初始规划', replanning: '重规划', decision: '决策', form_analysis: '单表分析',
    chunk_analysis: '分块分析', final_answer: '最终回答', presentation: '展示格式化' } as Record<string, string>)[purpose] || purpose
}
function statusLabel(status: string) { return ({ SUCCEEDED: '成功', PARTIAL: '部分成功', FAILED: '失败', RUNNING: '执行中', PENDING: '待续查', RETRYING: '重试中' } as Record<string, string>)[status] || status }
function statusColor(status: string) { return status === 'SUCCEEDED' ? 'green' : status === 'PARTIAL' ? 'orange' : ['RUNNING', 'PENDING', 'RETRYING'].includes(status) ? 'blue' : 'red' }
function escapeHtml(value: string) { return value.replace(/[&<>'"]/g, char => ({ '&': '&amp;', '<': '&lt;', '>': '&gt;', "'": '&#39;', '"': '&quot;' }[char] || char)) }
</script>

<style scoped>
.trace-alert { margin-bottom: 16px }
.metric-grid { display: grid; grid-template-columns: repeat(4, minmax(120px, 1fr)); gap: 12px; margin-bottom: 16px }
.metric-card { min-height: 76px; padding: 12px 14px; border: 1px solid #dbeafe; border-radius: 8px; background: #f8fafc; display: flex; flex-direction: column; justify-content: space-between }
.metric-card span { color: #64748b; font-size: 12px }
.metric-card strong { color: #1e3a8a; font-size: 22px; line-height: 1.2 }
.trace-section { margin-bottom: 16px }
.trace-graph { min-height: 180px; overflow: auto; padding: 8px }
.trace-filters { display: flex; align-items: center; flex-wrap: wrap; gap: 8px; margin-bottom: 12px }
.filter-count, .muted { color: #64748b; font-size: 12px }
.table-scroll { overflow-x: auto }
.node-label { display: inline-flex; align-items: center; min-width: 0 }
.node-label-child { padding-left: 24px; color: #475569 }
.group-caret { width: 0; height: 0; margin-right: 8px; border-top: 5px solid transparent; border-bottom: 5px solid transparent; border-left: 6px solid #1e40af; transition: transform 180ms ease }
.group-caret.expanded { transform: rotate(90deg) }
:deep(.form-group-row > td) { background: #f8fafc; font-weight: 500 }
:deep(.form-group-row:hover > td) { background: #eff6ff !important }
:deep(.form-child-row > td) { background: #ffffff }
.json-block { margin: 0; max-height: 520px; overflow: auto; white-space: pre-wrap; word-break: break-word; border: 1px solid #e2e8f0; border-radius: 6px; background: #f8fafc; padding: 16px; font: 12px/1.55 ui-monospace, SFMono-Regular, Menlo, Monaco, Consolas, monospace }
.usage-block { margin-top: 16px }
:deep(.graph-error) { color: #dc2626; padding: 16px }
@media (max-width: 1200px) { .metric-grid { grid-template-columns: repeat(3, minmax(120px, 1fr)) } }
@media (max-width: 640px) { .metric-grid { grid-template-columns: repeat(2, minmax(120px, 1fr)) } }
@media (prefers-reduced-motion: reduce) { .group-caret { transition: none } }
</style>
