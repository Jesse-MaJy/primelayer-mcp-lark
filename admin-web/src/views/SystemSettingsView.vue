<template>
  <section class="page system-settings">
    <div class="page-header">
      <div>
        <h1 class="page-title">系统配置</h1>
        <div class="page-subtitle">业务问答统一使用 DeepSeek；Base URL 与 API Key 继续由环境变量维护。</div>
      </div>
      <div class="toolbar">
        <a-button @click="loadSettings">刷新</a-button>
        <a-button type="primary" :loading="saving" @click="saveSettings">保存配置</a-button>
      </div>
    </div>

    <section class="settings-panel">
      <div class="section-title">DeepSeek 模型</div>
      <a-form layout="vertical">
        <a-form-item label="生产问答模型">
          <a-segmented v-model:value="form.deepSeekModel" :options="modelOptions" />
        </a-form-item>
        <a-alert type="info" show-icon message="V4-Pro 优先分析质量；V4-Flash 优先响应速度和成本。保存后仅影响新请求。" />
      </a-form>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { adminApi } from '../api/admin'

const saving = ref(false)
const supportedModels = ref<string[]>(['deepseek-v4-pro', 'deepseek-v4-flash'])
const form = reactive({ deepSeekModel: 'deepseek-v4-pro' })
const modelOptions = computed(() => supportedModels.value.map(model => ({
  label: model === 'deepseek-v4-pro' ? 'V4-Pro' : 'V4-Flash',
  value: model
})))

async function loadSettings() {
  try {
    const settings = await adminApi.getAiSettings()
    form.deepSeekModel = settings.deepSeekModel
    supportedModels.value = settings.supportedModels
  } catch (error) {
    showError(error)
  }
}

async function saveSettings() {
  saving.value = true
  try {
    const settings = await adminApi.saveAiSettings({ deepSeekModel: form.deepSeekModel })
    form.deepSeekModel = settings.deepSeekModel
    supportedModels.value = settings.supportedModels
    message.success('DeepSeek 模型配置已保存')
  } catch (error) {
    showError(error)
  } finally {
    saving.value = false
  }
}

function showError(error: unknown) {
  message.error(error instanceof Error ? error.message : '操作失败')
}

onMounted(loadSettings)
</script>

<style scoped>
.page-subtitle { margin-top: 6px; color: #5b667a; font-size: 13px; }
.settings-panel { max-width: 720px; background: #fff; border: 1px solid #e6eaf2; border-radius: 8px; padding: 20px; }
.section-title { margin-bottom: 14px; color: #172033; font-size: 15px; font-weight: 650; }
</style>
