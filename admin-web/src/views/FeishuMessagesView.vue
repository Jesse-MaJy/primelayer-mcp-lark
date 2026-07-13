<template>
  <section class="page">
    <div class="page-header">
      <div>
        <h1 class="page-title">飞书消息记录</h1>
        <div class="page-subtitle">只记录进入 Lark Connect Agent 的消息；若飞书里出现回复但这里没有记录，通常来自其他机器人进程或飞书平台其他回调配置。</div>
      </div>
      <a-button :loading="loading" @click="loadRows">刷新</a-button>
    </div>

    <a-table
      :columns="columns"
      :data-source="rows"
      :loading="loading"
      row-key="id"
      :scroll="scrollConfig"
      :pagination="{ pageSize: 20 }"
      @resizeColumn="onResizeColumn"
    >
      <template #bodyCell="{ column, record, text }">
        <template v-if="column.key === 'status'">
          <a-tag :color="statusColor(record.status)">{{ record.status || '-' }}</a-tag>
        </template>
        <template v-else-if="column.key === 'message_text'">
          <span class="clamp">{{ text || '-' }}</span>
        </template>
        <template v-else-if="column.key === 'final_answer'">
          <span class="clamp">{{ text || '-' }}</span>
        </template>
        <template v-else-if="column.key === 'counts'">
          <a-space>
            <a-tag>模型 {{ record.model_call_count ?? 0 }}</a-tag>
            <a-popover v-if="Number(record.tool_call_count || 0) > 0" trigger="click" placement="left">
              <template #title>MCP 工具调用</template>
              <template #content>
                <div style="max-width: 420px">
                  <a-space wrap :size="[4, 4]">
                    <a-tag
                      v-for="name in splitToolNames(record.tool_names)"
                      :key="name"
                      color="geekblue"
                    >
                      {{ name }}
                    </a-tag>
                  </a-space>
                  <div style="margin-top: 8px; color: #667085; font-size: 12px">
                    共 {{ record.tool_call_count ?? 0 }} 次调用，其中
                    {{ record.failed_tool_call_count ?? 0 }} 次失败
                  </div>
                  <a-button type="link" size="small" style="padding: 0; margin-top: 8px" @click.stop="openTrace(record.request_id)">
                    查看 MCP 调用链路 →
                  </a-button>
                </div>
              </template>
              <a-tag
                class="tool-tag"
                :color="Number(record.failed_tool_call_count || 0) > 0 ? 'red' : 'blue'"
              >
                工具 {{ record.tool_call_count ?? 0 }}
              </a-tag>
            </a-popover>
            <a-tag v-else>工具 0</a-tag>
          </a-space>
        </template>
        <template v-else-if="column.key === 'feedback'">
          <a-space :size="4" wrap>
            <a-tag color="green">有帮助 {{ record.helpful_count ?? 0 }}</a-tag>
            <a-tag :color="Number(record.problem_count || 0) > 0 ? 'red' : 'default'">
              有问题 {{ record.problem_count ?? 0 }}
            </a-tag>
            <a-button
              v-if="Number(record.feedback_count || 0) > 0"
              type="link"
              size="small"
              @click.stop="openFeedback(record)"
            >
              查看
            </a-button>
          </a-space>
        </template>
      </template>

      <template #expandedRowRender="{ record }">
        <div class="detail-grid">
          <div>
            <div class="detail-label">用户问题</div>
            <pre>{{ record.message_text || '-' }}</pre>
          </div>
          <div>
            <div class="detail-label">最终回答</div>
            <pre>{{ record.final_answer || '-' }}</pre>
          </div>
          <div>
            <div class="detail-label">错误</div>
            <pre>{{ record.task_error || record.audit_error || '-' }}</pre>
          </div>
          <div>
            <div class="detail-label">排查信息</div>
            <pre>{{ diagnosticText(record) }}</pre>
          </div>
          <div>
            <div class="detail-label">回答反馈</div>
            <a-space wrap>
              <a-tag color="green">有帮助 {{ record.helpful_count ?? 0 }}</a-tag>
              <a-tag :color="Number(record.problem_count || 0) > 0 ? 'red' : 'default'">
                有问题 {{ record.problem_count ?? 0 }}
              </a-tag>
              <a-button
                v-if="Number(record.feedback_count || 0) > 0"
                type="link"
                size="small"
                @click="openFeedback(record)"
              >查看反馈明细</a-button>
              <span v-else class="muted">暂无用户反馈</span>
            </a-space>
          </div>
          <div style="grid-column: 1 / -1; text-align: center; padding-top: 8px">
            <a-button type="primary" ghost size="small" @click="openTrace(record.request_id)">
              🔗 查看 MCP 调用链路图
            </a-button>
          </div>
        </div>
      </template>
    </a-table>

    <!-- Chain Trace Drawer -->
    <a-drawer
      :open="drawerOpen"
      title="MCP 调用链路"
      placement="right"
      :width="900"
      @close="drawerOpen = false"
    >
      <a-spin :spinning="traceLoading" style="display: block">
        <a-alert v-if="traceError" type="error" :message="traceError" showIcon style="margin-bottom: 16px" />

        <template v-if="traceData">
          <a-row :gutter="12" style="margin-bottom: 16px">
            <a-col :span="6">
              <a-card size="small" title="MCP 次数">
                <strong>{{ traceData.summary?.totalMcpCalls ?? 0 }}</strong>
              </a-card>
            </a-col>
            <a-col :span="6">
              <a-card size="small" title="总页数">
                <strong>{{ traceData.summary?.totalPages ?? 0 }}</strong>
              </a-card>
            </a-col>
            <a-col :span="6">
              <a-card size="small" title="总耗时">
                <strong>{{ formatMs(traceData.summary?.totalLatencyMs) }}</strong>
              </a-card>
            </a-col>
            <a-col :span="6">
              <a-card size="small" title="工具">
                <a-tag v-for="t in traceData.summary?.toolsUsed || []" :key="t" style="margin: 2px">{{ t }}</a-tag>
                <span v-if="!traceData.summary?.toolsUsed?.length">-</span>
              </a-card>
            </a-col>
          </a-row>

          <a-card title="调用流程" size="small" style="margin-bottom: 12px">
            <div ref="mermaidContainer" style="overflow-x: auto; min-height: 150px"></div>
          </a-card>

          <a-table :columns="nodeCols" :dataSource="traceData.nodes || []" rowKey="id" size="small" :pagination="false">
            <template #bodyCell="{ column, record: node }">
              <template v-if="column.key === 'type'">
                <a-tag :color="node.type === 'model_call' ? 'blue' : 'green'">
                  {{ node.type === 'model_call' ? '模型' : 'MCP' }}
                </a-tag>
              </template>
              <template v-if="column.key === 'status'">
                <a-tag :color="node.status === 'SUCCEEDED' ? 'green' : 'red'">{{ node.status }}</a-tag>
              </template>
              <template v-if="column.key === 'latency'">{{ formatMs(node.latencyMs) }}</template>
              <template v-if="column.key === 'detail'">
                <a-button type="link" size="small" @click="showNodeDetail(node)">详情</a-button>
              </template>
            </template>
          </a-table>
        </template>
      </a-spin>
    </a-drawer>

    <a-drawer
      :open="feedbackDrawerOpen"
      title="AI 回答反馈"
      placement="right"
      :width="720"
      @close="feedbackDrawerOpen = false"
    >
      <a-spin :spinning="feedbackLoading" style="display: block">
        <a-alert
          v-if="feedbackError"
          type="error"
          :message="feedbackError"
          show-icon
          style="margin-bottom: 16px"
        />
        <a-empty v-else-if="!feedbackLoading && feedbackRows.length === 0" description="暂无用户反馈" />
        <a-list v-else :data-source="feedbackRows" item-layout="vertical">
          <template #renderItem="{ item }">
            <a-list-item>
              <a-list-item-meta>
                <template #title>
                  <a-space>
                    <span>{{ item.personName || item.feishuOpenId || '-' }}</span>
                    <a-tag :color="item.rating === 'HELPFUL' ? 'green' : 'red'">
                      {{ item.rating === 'HELPFUL' ? '有帮助' : '有问题' }}
                    </a-tag>
                    <a-tag v-if="item.reasonLabel">{{ item.reasonLabel }}</a-tag>
                  </a-space>
                </template>
                <template #description>
                  {{ item.feishuOpenId }} · {{ formatChinaDateTime(item.updatedAt) }}
                </template>
              </a-list-item-meta>
              <pre v-if="item.detail" class="feedback-detail">{{ item.detail }}</pre>
            </a-list-item>
          </template>
        </a-list>
      </a-spin>
    </a-drawer>

    <!-- Node detail modal -->
    <a-modal v-model:open="nodeModalOpen" title="节点详情" :footer="null" width="800px">
      <a-tabs>
        <a-tab-pane key="input" tab="输入">
          <pre class="json-block">{{ detailInput || '(无)' }}</pre>
        </a-tab-pane>
        <a-tab-pane key="output" tab="输出">
          <pre class="json-block">{{ detailOutput || '(无)' }}</pre>
        </a-tab-pane>
        <a-tab-pane key="meta" tab="元信息">
          <a-descriptions size="small" bordered :column="2">
            <a-descriptions-item v-for="(v, k) in detailMeta" :key="String(k)" :label="String(k)">
              {{ typeof v === 'object' ? JSON.stringify(v) : String(v) }}
            </a-descriptions-item>
          </a-descriptions>
        </a-tab-pane>
      </a-tabs>
    </a-modal>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref, nextTick } from 'vue'
import { message } from 'ant-design-vue'
import { adminApi, type AnswerFeedbackDetail } from '../api/admin'
import { formatChinaDateTime } from '../utils/time'

const rows = ref<Record<string, unknown>[]>([])
const loading = ref(false)

const columns = ref([
  { title: '时间', dataIndex: 'created_at', width: 170, resizable: true, minWidth: 80, maxWidth: 400, customRender: ({ text }: { text: unknown }) => formatChinaDateTime(text) },
  { title: '状态', dataIndex: 'status', key: 'status', width: 100, resizable: true, minWidth: 60, maxWidth: 200 },
  { title: '人员', dataIndex: 'person_name', width: 110, resizable: true, minWidth: 60, maxWidth: 300, customRender: ({ text, record }: { text: unknown; record: Record<string, unknown> }) => text || record.feishu_open_id || '-' },
  { title: '会话', dataIndex: 'chat_type', width: 80, resizable: true, minWidth: 60, maxWidth: 200 },
  { title: '问题', dataIndex: 'message_text', key: 'message_text', width: 240, resizable: true, minWidth: 80, maxWidth: 800 },
  { title: 'Intent', dataIndex: 'intent', width: 140, resizable: true, minWidth: 80, maxWidth: 400 },
  { title: '调用', key: 'counts', width: 150, resizable: true, minWidth: 80, maxWidth: 400 },
  { title: '反馈', key: 'feedback', width: 210, resizable: true, minWidth: 150, maxWidth: 400 },
  { title: '耗时', dataIndex: 'latency_ms', width: 90, resizable: true, minWidth: 60, maxWidth: 200, customRender: ({ text }: { text: unknown }) => text == null ? '-' : `${text} ms` },
  { title: '回答', dataIndex: 'final_answer', key: 'final_answer', width: 300, resizable: true, minWidth: 80, maxWidth: 1000 }
])

// Chain trace drawer
const drawerOpen = ref(false)
const traceLoading = ref(false)
const traceError = ref('')
const traceData = ref<Record<string, any> | null>(null)
const mermaidContainer = ref<HTMLDivElement>()

const nodeCols = [
  { title: '节点', dataIndex: 'label', ellipsis: true },
  { title: '类型', key: 'type', width: 60 },
  { title: '状态', key: 'status', width: 70 },
  { title: '耗时', key: 'latency', width: 70 },
  { title: '详情', key: 'detail', width: 60 }
]

// Node detail modal
const nodeModalOpen = ref(false)
const detailInput = ref('')
const detailOutput = ref('')
const detailMeta = ref<Record<string, unknown>>({})

const feedbackDrawerOpen = ref(false)
const feedbackLoading = ref(false)
const feedbackError = ref('')
const feedbackRows = ref<AnswerFeedbackDetail[]>([])

function onResizeColumn(width: number, column: { width?: number }) {
  column.width = width
}

const scrollConfig = computed(() => ({
  x: columns.value.reduce((sum, col) => sum + (Number(col.width) || 0), 0)
}))

async function loadRows() {
  loading.value = true
  try {
    rows.value = await adminApi.listFeishuMessages()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载消息记录失败')
  } finally {
    loading.value = false
  }
}

async function openTrace(requestId: unknown) {
  drawerOpen.value = true
  traceLoading.value = true
  traceError.value = ''
  traceData.value = null
  try {
    const data = await adminApi.getChainTrace(String(requestId))
    traceData.value = data
    await nextTick()
    renderMermaid()
  } catch (e: any) {
    traceError.value = e?.message || '加载链调数据失败'
  } finally {
    traceLoading.value = false
  }
}

async function openFeedback(record: Record<string, unknown>) {
  feedbackDrawerOpen.value = true
  feedbackLoading.value = true
  feedbackError.value = ''
  feedbackRows.value = []
  try {
    feedbackRows.value = await adminApi.listMessageFeedback(String(record.request_id || ''))
  } catch (error) {
    feedbackError.value = error instanceof Error ? error.message : '加载反馈明细失败'
  } finally {
    feedbackLoading.value = false
  }
}

async function renderMermaid() {
  if (!traceData.value || !mermaidContainer.value) return
  try {
    const mermaid = (await import('mermaid')).default
    mermaid.initialize({ startOnLoad: false, theme: 'default', securityLevel: 'loose' })

    const nodes: any[] = traceData.value.nodes || []
    const edges: any[] = traceData.value.edges || []

    let chart = 'graph TD\n'
    for (const node of nodes) {
      const label = String(node.label || node.id).replace(/[()]/g, '').replace(/"/g, "'")
      chart += `  ${node.id}["${label}"]\n`
      if (node.status === 'SUCCEEDED') {
        chart += `  style ${node.id} fill:${node.type === 'model_call' ? '#e6f7ff' : '#f6ffed'},stroke:${node.type === 'model_call' ? '#1890ff' : '#52c41a'}\n`
      } else {
        chart += `  style ${node.id} fill:#fff2f0,stroke:#ff4d4f\n`
      }
    }
    for (const edge of edges) {
      chart += `  ${edge.from} --> ${edge.to}\n`
    }

    const { svg } = await mermaid.render('mermaid-msg-chart', chart)
    mermaidContainer.value.innerHTML = svg
  } catch (e: any) {
    console.error('Mermaid render failed:', e)
    if (mermaidContainer.value) {
      mermaidContainer.value.innerHTML = '<p style="color:red">流程图渲染失败</p>'
    }
  }
}

function showNodeDetail(node: any) {
  detailInput.value = formatJson(node.input || node.request || '')
  detailOutput.value = formatJson(node.output || node.response || '')
  detailMeta.value = { ...node }
  delete (detailMeta.value as any).input
  delete (detailMeta.value as any).output
  delete (detailMeta.value as any).request
  delete (detailMeta.value as any).response
  nodeModalOpen.value = true
}

function formatJson(value: string): string {
  if (!value) return ''
  try { return JSON.stringify(JSON.parse(value), null, 2) } catch { return value }
}

function formatMs(ms: number | undefined): string {
  if (!ms) return '-'
  return ms >= 1000 ? (ms / 1000).toFixed(1) + 's' : ms + 'ms'
}

function statusColor(status: unknown) {
  if (status === 'SUCCEEDED') return 'green'
  if (status === 'FAILED') return 'red'
  if (status === 'RUNNING') return 'blue'
  return 'default'
}

function diagnosticText(record: Record<string, unknown>) {
  return [
    `request_id: ${record.request_id || '-'}`,
    `message_id: ${record.feishu_message_id || '-'}`,
    `open_id: ${record.feishu_open_id || '-'}`,
    `chat_id: ${record.feishu_chat_id || '-'}`,
    `primelayer_user_id: ${record.primelayer_user_id || '-'}`,
    `model_calls: ${record.model_call_count ?? 0}`,
    `tool_calls: ${record.tool_call_count ?? 0}`,
    `failed_tool_calls: ${record.failed_tool_call_count ?? 0}`,
    `tool_names: ${record.tool_names || '-'}`,
    `started_at: ${formatChinaDateTime(record.started_at)}`,
    `finished_at: ${formatChinaDateTime(record.finished_at)}`
  ].join('\n')
}

function splitToolNames(raw: unknown): string[] {
  if (typeof raw !== 'string' || !raw.trim()) return []
  return raw.split(',').map(s => s.trim()).filter(Boolean)
}

onMounted(loadRows)
</script>

<style scoped>
.page-subtitle { margin-top: 6px; color: #667085; font-size: 13px }
.tool-tag { cursor: pointer; user-select: none }
.tool-tag:hover { opacity: 0.85 }
.clamp { display: -webkit-box; -webkit-line-clamp: 2; -webkit-box-orient: vertical; overflow: hidden; white-space: normal }
.detail-grid { display: grid; grid-template-columns: repeat(2, minmax(0, 1fr)); gap: 16px }
.detail-label { font-weight: 600; margin-bottom: 6px }
.muted { color: #98a2b3 }
.feedback-detail { max-height: 180px }
pre { margin: 0; white-space: pre-wrap; word-break: break-word; background: #f7f8fa; border: 1px solid #e5e7eb; border-radius: 6px; padding: 10px; max-height: 260px; overflow: auto }
.json-block { background: #f5f5f5; padding: 16px; border-radius: 4px; max-height: 500px; overflow: auto; white-space: pre-wrap; word-break: break-all; font-size: 12px; line-height: 1.5; margin: 0 }
@media (max-width: 900px) { .detail-grid { grid-template-columns: 1fr } }
</style>
