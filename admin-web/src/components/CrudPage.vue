<template>
  <section class="page">
    <div class="page-header">
      <h1 class="page-title">{{ title }}</h1>
      <div class="toolbar">
        <a-button @click="refresh">刷新</a-button>
        <a-button type="primary" @click="open">新增 / 替换</a-button>
      </div>
    </div>
    <a-table :columns="columns" :data-source="rows" :loading="loading" row-key="id" :scroll="{ x: 960 }" />
    <a-modal v-model:open="visible" :title="title" @ok="submit">
      <a-form layout="vertical">
        <a-form-item v-for="field in fields" :key="field.name" :label="field.label" :required="field.required">
          <a-input-password v-if="field.password" v-model:value="form[field.name]" />
          <a-input v-else v-model:value="form[field.name]" />
        </a-form-item>
      </a-form>
    </a-modal>
  </section>
</template>

<script setup lang="ts">
import { onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'

interface FieldDef {
  name: string
  label: string
  required?: boolean
  password?: boolean
  defaultValue?: string
}

const props = defineProps<{
  title: string
  columns: Record<string, unknown>[]
  fields: FieldDef[]
  load: () => Promise<Record<string, unknown>[]>
  save: (payload: Record<string, unknown>) => Promise<unknown>
}>()

const rows = ref<Record<string, unknown>[]>([])
const loading = ref(false)
const visible = ref(false)
const form = reactive<Record<string, string>>({})

async function refresh() {
  loading.value = true
  try {
    rows.value = await props.load()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '加载失败')
  } finally {
    loading.value = false
  }
}

function open() {
  for (const field of props.fields) {
    form[field.name] = field.defaultValue || ''
  }
  visible.value = true
}

async function submit() {
  try {
    await props.save({ ...form })
    visible.value = false
    message.success('已保存')
    await refresh()
  } catch (error) {
    message.error(error instanceof Error ? error.message : '保存失败')
  }
}

onMounted(refresh)
</script>
