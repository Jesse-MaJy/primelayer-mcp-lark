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
        </div>
      </template>
    </a-table>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'
import { adminApi } from '../api/admin'
import { formatChinaDateTime } from '../utils/time'

const rows = ref<Record<string, unknown>[]>([])
const loading = ref(false)

const columns = ref([
  { title: '时间', dataIndex: 'created_at', width: 170, resizable: true, minWidth: 80, maxWidth: 400, customRender: ({ text }: { text: unknown }) => formatChinaDateTime(text) },
  { title: '状态', dataIndex: 'status', key: 'status', width: 110, resizable: true, minWidth: 60, maxWidth: 200 },
  { title: '人员', dataIndex: 'person_name', width: 120, resizable: true, minWidth: 60, maxWidth: 300, customRender: ({ text, record }: { text: unknown; record: Record<string, unknown> }) => text || record.feishu_open_id || '-' },
  { title: '会话类型', dataIndex: 'chat_type', width: 100, resizable: true, minWidth: 60, maxWidth: 200 },
  { title: '问题', dataIndex: 'message_text', key: 'message_text', width: 280, resizable: true, minWidth: 80, maxWidth: 800 },
  { title: 'Intent / Skill', dataIndex: 'intent', width: 160, resizable: true, minWidth: 80, maxWidth: 400 },
  { title: '项目', dataIndex: 'project_ids', width: 180, resizable: true, minWidth: 80, maxWidth: 500 },
  { title: '调用', key: 'counts', width: 170, resizable: true, minWidth: 80, maxWidth: 400 },
  { title: '耗时', dataIndex: 'latency_ms', width: 100, resizable: true, minWidth: 60, maxWidth: 200, customRender: ({ text }: { text: unknown }) => text == null ? '-' : `${text} ms` },
  { title: '回答', dataIndex: 'final_answer', key: 'final_answer', width: 340, resizable: true, minWidth: 80, maxWidth: 1000 },
  { title: '消息 ID', dataIndex: 'feishu_message_id', width: 260, resizable: true, minWidth: 80, maxWidth: 600 },
  { title: '请求 ID', dataIndex: 'request_id', width: 260, resizable: true, minWidth: 80, maxWidth: 600 }
])

function onResizeColumn(width: number, column: { width?: number }) {
  column.width = width
}

// scroll.x 必须随列宽之和动态变化，否则 table-layout: fixed 下表格总宽被锁死，
// 拖拽列宽时只是在一块固定宽度内重新分配，整体宽度不会变化。
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
.page-subtitle {
  margin-top: 6px;
  color: #667085;
  font-size: 13px;
}

.tool-tag {
  cursor: pointer;
  user-select: none;
}

.tool-tag:hover {
  opacity: 0.85;
}

.clamp {
  display: -webkit-box;
  -webkit-line-clamp: 2;
  -webkit-box-orient: vertical;
  overflow: hidden;
  white-space: normal;
}

.detail-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 16px;
}

.detail-label {
  font-weight: 600;
  margin-bottom: 6px;
}

pre {
  margin: 0;
  white-space: pre-wrap;
  word-break: break-word;
  background: #f7f8fa;
  border: 1px solid #e5e7eb;
  border-radius: 6px;
  padding: 10px;
  max-height: 260px;
  overflow: auto;
}

@media (max-width: 900px) {
  .detail-grid {
    grid-template-columns: 1fr;
  }
}
</style>
