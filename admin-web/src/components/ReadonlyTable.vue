<template>
  <section class="page">
    <div class="page-header">
      <h1 class="page-title">{{ title }}</h1>
      <a-button @click="refresh">刷新</a-button>
    </div>
    <a-table :columns="columns" :data-source="rows" :loading="loading" row-key="id" :scroll="{ x: 1200 }" />
  </section>
</template>

<script setup lang="ts">
import { onMounted, ref } from 'vue'
import { message } from 'ant-design-vue'

const props = defineProps<{
  title: string
  columns: Record<string, unknown>[]
  load: () => Promise<Record<string, unknown>[]>
}>()

const rows = ref<Record<string, unknown>[]>([])
const loading = ref(false)

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

onMounted(refresh)
</script>
