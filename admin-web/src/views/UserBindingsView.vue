<template>
  <CrudPage
    title="用户绑定"
    :columns="columns"
    :load="adminApi.listUserBindings"
    :save="adminApi.saveUserBinding"
    :fields="fields"
  />
</template>

<script setup lang="ts">
import CrudPage from '../components/CrudPage.vue'
import { adminApi } from '../api/admin'
import { timeColumn } from '../utils/time'

const columns = [
  { title: '人名', dataIndex: 'person_name' },
  { title: '飞书 open_id', dataIndex: 'feishu_open_id' },
  { title: 'Primelayer 用户 ID', dataIndex: 'primelayer_user_id' },
  { title: 'Primelayer 用户名', dataIndex: 'primelayer_user_name' },
  { title: '状态', dataIndex: 'status' },
  timeColumn('更新时间', 'updated_at')
]

const fields = [
  { name: 'personName', label: '人名' },
  { name: 'feishuOpenId', label: '飞书 open_id', required: true },
  { name: 'primelayerUserId', label: 'Primelayer 用户 ID', required: true },
  { name: 'primelayerUserName', label: 'Primelayer 用户名' },
  { name: 'status', label: '状态', defaultValue: 'ACTIVE' }
]
</script>
