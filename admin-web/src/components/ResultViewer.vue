<template>
  <section class="result-viewer">
    <div class="result-header">
      <h2>返回结果</h2>
      <a-button size="small" @click="copy">复制</a-button>
    </div>
    <pre>{{ formatted }}</pre>
  </section>
</template>

<script setup lang="ts">
import { computed } from 'vue'
import { message } from 'ant-design-vue'

const props = defineProps<{
  data: unknown
}>()

const formatted = computed(() => {
  if (props.data === null || props.data === undefined) {
    return '暂无结果'
  }
  return JSON.stringify(props.data, null, 2)
})

async function copy() {
  await navigator.clipboard.writeText(formatted.value)
  message.success('已复制')
}
</script>

<style scoped>
.result-viewer {
  background: #fff;
  border: 1px solid #e6eaf2;
  border-radius: 8px;
  overflow: hidden;
}

.result-header {
  height: 44px;
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 12px 0 16px;
  border-bottom: 1px solid #e6eaf2;
}

h2 {
  margin: 0;
  font-size: 14px;
  font-weight: 650;
}

pre {
  min-height: 320px;
  max-height: 680px;
  margin: 0;
  padding: 16px;
  overflow: auto;
  font-size: 12px;
  line-height: 1.6;
  white-space: pre-wrap;
  word-break: break-word;
  background: #fbfcff;
}
</style>
