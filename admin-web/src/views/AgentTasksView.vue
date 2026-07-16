<template>
  <div>
    <a-table :columns="columns" :dataSource="data" :loading="loading" rowKey="request_id" size="small" :pagination="false">
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'status'">
          <a-tag :color="statusColor(record.status)">{{ record.status }}</a-tag>
        </template>
        <template v-if="column.key === 'actions'">
          <a-button type="link" size="small" @click="viewTrace(record.request_id)">链调详情</a-button>
        </template>
      </template>
    </a-table>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { useRouter } from 'vue-router'
import { adminApi } from '../api/admin'

const router = useRouter()
const loading = ref(false)
const data = ref<Record<string, unknown>[]>([])

const columns = [
  { title: '请求 ID', dataIndex: 'request_id', key: 'request_id', width: 240 },
  { title: '消息 ID', dataIndex: 'feishu_message_id', key: 'message_id' },
  { title: '飞书 open_id', dataIndex: 'feishu_open_id', key: 'open_id' },
  { title: '会话', dataIndex: 'feishu_chat_id', key: 'chat_id' },
  { title: '状态', key: 'status', width: 100 },
  { title: '操作', key: 'actions', width: 120 }
]

onMounted(async () => {
  loading.value = true
  try {
    const result = await adminApi.listAgentTasks() as Record<string, unknown>[]
    data.value = result || []
  } finally {
    loading.value = false
  }
})

function viewTrace(requestId: string) {
  router.push(`/chain-trace/${requestId}`)
}

function statusColor(status: string) {
  switch (status) {
    case 'SUCCEEDED': return 'green'
    case 'FAILED': return 'red'
    case 'RUNNING': return 'blue'
    case 'CANCELLED': return 'default'
    default: return 'default'
  }
}
</script>
