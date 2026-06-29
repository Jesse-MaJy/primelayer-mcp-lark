<template>
  <ReadonlyTable title="任务状态" :columns="columns" :load="adminApi.listAgentTasks" />
</template>

<script setup lang="ts">
import ReadonlyTable from '../components/ReadonlyTable.vue'
import { adminApi } from '../api/admin'

const columns = [
  { title: '请求 ID', dataIndex: 'request_id', width: 240 },
  { title: '消息 ID', dataIndex: 'feishu_message_id' },
  { title: '飞书 open_id', dataIndex: 'feishu_open_id' },
  { title: '会话', dataIndex: 'feishu_chat_id' },
  { title: '状态', dataIndex: 'status' },
  { title: '错误', dataIndex: 'error_message' },
  { title: '创建时间', dataIndex: 'created_at' },
  { title: '完成时间', dataIndex: 'finished_at' }
]
</script>
