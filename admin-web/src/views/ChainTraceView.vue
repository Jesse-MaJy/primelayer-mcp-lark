<template>
  <div style="padding: 24px">
    <a-page-header title="MCP 调用链路" @back="() => $router.back()">
      <template #subTitle>
        请求ID: {{ requestId }}
        <a-tag v-if="traceData" :color="traceData.summary?.totalPages > 0 ? 'green' : 'red'" style="margin-left: 8px">
          {{ traceData.summary?.totalMcpCalls ?? 0 }} 次 MCP 调用 / {{ traceData.summary?.totalPages ?? 0 }} 页
        </a-tag>
      </template>
    </a-page-header>

    <a-spin :spinning="loading" style="display: block">
      <a-alert v-if="error" type="error" :message="error" showIcon style="margin-bottom: 16px" />

      <template v-if="traceData">
        <a-row :gutter="16" style="margin-bottom: 16px">
          <a-col :span="6">
            <a-card size="small" title="MCP 调用次数">
              <span style="font-size: 24px; font-weight: bold">{{ traceData.summary?.totalMcpCalls ?? 0 }}</span>
            </a-card>
          </a-col>
          <a-col :span="6">
            <a-card size="small" title="总页数">
              <span style="font-size: 24px; font-weight: bold">{{ traceData.summary?.totalPages ?? 0 }}</span>
            </a-card>
          </a-col>
          <a-col :span="6">
            <a-card size="small" title="总耗时">
              <span style="font-size: 24px; font-weight: bold">{{ formatMs(traceData.summary?.totalLatencyMs) }}</span>
            </a-card>
          </a-col>
          <a-col :span="6">
            <a-card size="small" title="使用工具">
              <a-tag v-for="t in (traceData.summary?.toolsUsed || [])" :key="t" style="margin: 2px">{{ t }}</a-tag>
              <span v-if="!traceData.summary?.toolsUsed?.length">-</span>
            </a-card>
          </a-col>
        </a-row>

        <a-card title="调用流程图" style="margin-bottom: 16px">
          <div ref="mermaidContainer" style="overflow-x: auto; min-height: 200px"></div>
        </a-card>

        <a-modal v-model:open="modalOpen" :title="modalTitle" width="900px" :footer="null">
          <a-tabs>
            <a-tab-pane key="input" tab="输入">
              <pre class="json-block">{{ modalInput || '(无)' }}</pre>
            </a-tab-pane>
            <a-tab-pane key="output" tab="输出">
              <pre class="json-block">{{ modalOutput || '(无)' }}</pre>
            </a-tab-pane>
            <a-tab-pane key="meta" tab="元信息">
              <a-descriptions size="small" bordered :column="2">
                <a-descriptions-item v-for="(v, k) in modalMeta" :key="String(k)" :label="String(k)">
                  {{ typeof v === 'object' ? JSON.stringify(v) : String(v) }}
                </a-descriptions-item>
              </a-descriptions>
            </a-tab-pane>
          </a-tabs>
        </a-modal>

        <a-card title="节点明细">
          <a-table :columns="nodeColumns" :dataSource="traceData.nodes || []"
            rowKey="id" size="small" :pagination="{ pageSize: 20 }">
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'type'">
                <a-tag :color="record.type === 'model_call' ? 'blue' : 'green'">
                  {{ record.type === 'model_call' ? '模型调用' : 'MCP 调用' }}
                </a-tag>
              </template>
              <template v-if="column.key === 'status'">
                <a-tag :color="record.status === 'SUCCEEDED' ? 'green' : 'red'">{{ record.status }}</a-tag>
              </template>
              <template v-if="column.key === 'latency'">
                {{ formatMs(record.latencyMs) }}
              </template>
              <template v-if="column.key === 'action'">
                <a-button type="link" size="small" @click="openNodeDetail(record)">详情</a-button>
              </template>
            </template>
          </a-table>
        </a-card>
      </template>
    </a-spin>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted, nextTick } from 'vue'
import { useRoute } from 'vue-router'
import { adminApi } from '../api/admin'

const route = useRoute()
const requestId = String(route.params.requestId)
const loading = ref(true)
const error = ref('')
const traceData = ref<Record<string, any> | null>(null)
const mermaidContainer = ref<HTMLDivElement>()

const modalOpen = ref(false)
const modalTitle = ref('')
const modalInput = ref('')
const modalOutput = ref('')
const modalMeta = ref<Record<string, unknown>>({})

const nodeColumns = [
  { title: '节点', dataIndex: 'label', key: 'label', ellipsis: true },
  { title: '类型', key: 'type', width: 90 },
  { title: '状态', key: 'status', width: 90 },
  { title: '耗时', key: 'latency', width: 80 },
  { title: '操作', key: 'action', width: 80 }
]

onMounted(async () => {
  try {
    const data = await adminApi.getChainTrace(requestId)
    traceData.value = data
    await nextTick()
    renderMermaid()
  } catch (e: any) {
    error.value = e?.message || '加载失败'
  } finally {
    loading.value = false
  }
})

async function renderMermaid() {
  if (!traceData.value || !mermaidContainer.value) return
  try {
    const mermaid = (await import('mermaid')).default
    mermaid.initialize({ startOnLoad: false, theme: 'default', securityLevel: 'loose' })

    const nodes: any[] = traceData.value.nodes || []
    const edges: any[] = traceData.value.edges || []

    let chart = 'graph TD\n'
    for (const node of nodes) {
      const label = (node.label || node.id).replace(/[()]/g, '').replace(/"/g, "'")
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

    const { svg } = await mermaid.render('mermaid-chart', chart)
    mermaidContainer.value.innerHTML = svg

    // Add click handlers to SVG elements
    for (const node of nodes) {
      const el = mermaidContainer.value.querySelector<HTMLElement>(`[id="${node.id}"]`)
      if (el) {
        el.style.cursor = 'pointer'
        el.addEventListener('click', () => openNodeDetail(node))
      }
    }
  } catch (e: any) {
    console.error('Mermaid render failed:', e)
    if (mermaidContainer.value) {
      mermaidContainer.value.innerHTML = '<p style="color:red;padding:16px">流程图渲染失败: ' + (e.message || '') + '</p>'
    }
  }
}

function openNodeDetail(node: any) {
  modalTitle.value = node.label || node.id
  modalInput.value = formatJson(node.input || node.request || '')
  modalOutput.value = formatJson(node.output || node.response || '')
  modalMeta.value = { ...node }
  delete (modalMeta.value as any).input
  delete (modalMeta.value as any).output
  delete (modalMeta.value as any).request
  delete (modalMeta.value as any).response
  modalOpen.value = true
}

function formatJson(value: string): string {
  if (!value) return ''
  try {
    return JSON.stringify(JSON.parse(value), null, 2)
  } catch {
    return value
  }
}

function formatMs(ms: number | undefined): string {
  if (!ms) return '-'
  return ms >= 1000 ? (ms / 1000).toFixed(1) + 's' : ms + 'ms'
}
</script>

<style scoped>
.json-block {
  background: #f5f5f5;
  padding: 16px;
  border-radius: 4px;
  max-height: 500px;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-all;
  font-size: 12px;
  line-height: 1.5;
  margin: 0;
}
</style>
