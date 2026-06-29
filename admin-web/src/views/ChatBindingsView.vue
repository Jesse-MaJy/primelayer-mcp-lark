<template>
  <CrudPage
    title="群项目绑定"
    :columns="columns"
    :load="adminApi.listChatBindings"
    :save="adminApi.saveChatBinding"
    :fields="fields"
  />
</template>

<script setup lang="ts">
import CrudPage from '../components/CrudPage.vue'
import { adminApi } from '../api/admin'

const columns = [
  { title: '飞书群 ID', dataIndex: 'feishu_chat_id' },
  { title: '项目 ID', dataIndex: 'project_id' },
  { title: '项目名称', dataIndex: 'project_name' },
  { title: '状态', dataIndex: 'status' },
  { title: '创建人', dataIndex: 'created_by' },
  { title: '更新时间', dataIndex: 'updated_at' }
]

const fields = [
  { name: 'feishuChatId', label: '飞书群 ID', required: true },
  { name: 'projectId', label: '项目 ID', required: true },
  { name: 'projectName', label: '项目名称', required: true },
  { name: 'status', label: '状态', defaultValue: 'ACTIVE' }
]
</script>
