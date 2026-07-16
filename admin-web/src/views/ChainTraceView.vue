<template>
  <div class="trace-page">
    <a-page-header title="MCP 调用链路" @back="() => $router.back()">
      <template #subTitle>请求 ID：{{ requestId }}</template>
    </a-page-header>
    <TracePanel :request-id="requestId" />
  </div>
</template>

<script setup lang="ts">
import { useRoute } from 'vue-router'
import TracePanel from '../components/TracePanel.vue'

const route = useRoute()
const requestId = String(route.params.requestId || '')
</script>

<style scoped>
.trace-page { padding: 24px }
</style>
