<template>
  <CrudPage
    title="项目 MCP Token"
    :columns="columns"
    :load="adminApi.listProjectTokens"
    :save="adminApi.saveProjectToken"
    :fields="fields"
  />
</template>

<script setup lang="ts">
import CrudPage from '../components/CrudPage.vue'
import { adminApi } from '../api/admin'
import { timeColumn } from '../utils/time'

const columns = [
  { title: 'Primelayer 用户 ID', dataIndex: 'primelayer_user_id' },
  { title: '项目 ID', dataIndex: 'project_id' },
  { title: '项目名称', dataIndex: 'project_name' },
  { title: 'Token Hash 后缀', dataIndex: 'token_hash_suffix' },
  { title: '状态', dataIndex: 'token_status' },
  { title: '导入人', dataIndex: 'imported_by' },
  timeColumn('最近使用', 'last_used_at')
]

const fields = [
  { name: 'primelayerUserId', label: 'Primelayer 用户 ID', required: true },
  { name: 'projectId', label: '项目 ID', required: true },
  { name: 'projectName', label: '项目名称', required: true },
  { name: 'mcpToken', label: 'MCP Token', required: true, password: true },
  { name: 'tokenStatus', label: '状态', defaultValue: 'ACTIVE' }
]
</script>
