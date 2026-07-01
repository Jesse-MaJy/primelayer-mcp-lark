<template>
  <ReadonlyTable title="审计日志" :columns="columns" :load="adminApi.listAuditLogs" />
</template>

<script setup lang="ts">
import ReadonlyTable from '../components/ReadonlyTable.vue'
import { adminApi } from '../api/admin'
import { timeColumn } from '../utils/time'

const columns = [
  { title: '请求 ID', dataIndex: 'request_id', width: 240 },
  { title: '飞书 open_id', dataIndex: 'feishu_open_id' },
  { title: 'Primelayer 用户', dataIndex: 'primelayer_user_id' },
  { title: '项目', dataIndex: 'project_ids' },
  { title: '意图', dataIndex: 'intent' },
  { title: '耗时', dataIndex: 'latency_ms' },
  { title: '错误', dataIndex: 'error_message' },
  timeColumn('创建时间', 'created_at')
]
</script>
