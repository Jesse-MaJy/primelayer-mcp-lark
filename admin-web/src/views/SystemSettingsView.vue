<template>
  <section class="page system-settings">
    <div class="page-header">
      <div>
        <h1 class="page-title">系统配置</h1>
        <div class="page-subtitle">切换 AI 问答引擎，并维护 FastGPT 接入参数。密钥保存后不回显。</div>
      </div>
      <div class="toolbar">
        <a-button @click="loadSettings">刷新</a-button>
        <a-button type="primary" :loading="saving" @click="saveSettings">保存配置</a-button>
      </div>
    </div>

    <div class="settings-grid">
      <section class="settings-panel">
        <div class="section-title">问答引擎</div>
        <a-form layout="vertical">
          <a-form-item label="当前引擎">
            <a-segmented
              v-model:value="form.engine"
              :options="engineOptions"
            />
          </a-form-item>
          <a-alert
            :type="form.engine === 'FASTGPT' ? 'warning' : 'info'"
            show-icon
            :message="engineHint"
          />
        </a-form>
      </section>

      <section class="settings-panel">
        <div class="section-title">FastGPT</div>
        <a-form layout="vertical">
          <a-form-item label="BaseURL">
            <a-input v-model:value="form.fastGptBaseUrl" placeholder="https://your-fastgpt-domain" />
          </a-form-item>
          <a-form-item label="模型">
            <a-input v-model:value="form.fastGptModel" placeholder="fastgpt" />
          </a-form-item>
          <a-form-item label="API Key">
            <a-input-password v-model:value="form.fastGptApiKey" :placeholder="apiKeyPlaceholder" />
          </a-form-item>
          <div class="compact-grid">
            <a-form-item label="超时">
              <a-input-number v-model:value="form.fastGptTimeoutMs" :min="1000" :max="120000" :step="1000" addon-after="ms" />
            </a-form-item>
            <a-form-item label="会话记忆">
              <a-switch v-model:checked="form.fastGptMemoryEnabled" checked-children="开启" un-checked-children="关闭" />
            </a-form-item>
          </div>
        </a-form>
      </section>
    </div>

    <section class="settings-panel connection-panel">
      <div class="section-title">连接测试</div>
      <a-form layout="vertical">
        <a-form-item label="测试提示词">
          <a-input v-model:value="testPrompt" />
        </a-form-item>
        <div class="toolbar">
          <a-button :loading="testing" @click="testFastGpt">测试 FastGPT</a-button>
        </div>
      </a-form>
      <a-descriptions v-if="testResult" class="result-block" bordered :column="2" size="small">
        <a-descriptions-item label="状态">
          <a-tag :color="testResult.ok ? 'green' : 'red'">{{ testResult.ok ? '连接成功' : '连接失败' }}</a-tag>
        </a-descriptions-item>
        <a-descriptions-item label="模型">{{ testResult.model || '-' }}</a-descriptions-item>
        <a-descriptions-item label="耗时">{{ testResult.latencyMs ?? '-' }} ms</a-descriptions-item>
        <a-descriptions-item label="API Key">{{ testResult.apiKeyConfigured ? '已配置' : '未配置' }}</a-descriptions-item>
        <a-descriptions-item label="返回" :span="2">{{ testResult.answer || testResult.error || '-' }}</a-descriptions-item>
      </a-descriptions>
    </section>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { adminApi, type AiSettings } from '../api/admin'

const saving = ref(false)
const testing = ref(false)
const apiKeyConfigured = ref(false)
const testPrompt = ref('请只回复 OK，用于测试 FastGPT API 连通性。')
const testResult = ref<Record<string, unknown> | null>(null)

const form = reactive({
  engine: 'LOCAL_AGENT' as AiSettings['engine'],
  fastGptBaseUrl: '',
  fastGptModel: 'fastgpt',
  fastGptApiKey: '',
  fastGptTimeoutMs: 30000,
  fastGptMemoryEnabled: true
})

const engineOptions = [
  { label: '本地 Agent', value: 'LOCAL_AGENT' },
  { label: 'FastGPT', value: 'FASTGPT' }
]

const engineHint = computed(() =>
  form.engine === 'FASTGPT'
    ? '生产飞书问答会优先调用 FastGPT；失败或超时会自动回退本地 Agent。'
    : '生产飞书问答使用现有 agent-service / DeepSeek / MCP 链路。'
)
const apiKeyPlaceholder = computed(() => apiKeyConfigured.value ? '已配置；留空表示不替换' : '请输入 FastGPT API Key')

async function loadSettings() {
  try {
    const settings = await adminApi.getAiSettings()
    form.engine = settings.engine
    form.fastGptBaseUrl = settings.fastGptBaseUrl || ''
    form.fastGptModel = settings.fastGptModel || 'fastgpt'
    form.fastGptApiKey = ''
    form.fastGptTimeoutMs = settings.fastGptTimeoutMs || 30000
    form.fastGptMemoryEnabled = settings.fastGptMemoryEnabled
    apiKeyConfigured.value = settings.fastGptApiKeyConfigured
  } catch (error) {
    showError(error)
  }
}

async function saveSettings() {
  saving.value = true
  try {
    const settings = await adminApi.saveAiSettings({ ...form })
    apiKeyConfigured.value = settings.fastGptApiKeyConfigured
    form.fastGptApiKey = ''
    message.success('配置已保存')
  } catch (error) {
    showError(error)
  } finally {
    saving.value = false
  }
}

async function testFastGpt() {
  testing.value = true
  try {
    testResult.value = await adminApi.debugFastGptConnection({ prompt: testPrompt.value })
  } catch (error) {
    showError(error)
  } finally {
    testing.value = false
  }
}

function showError(error: unknown) {
  message.error(error instanceof Error ? error.message : '操作失败')
}

onMounted(loadSettings)
</script>

<style scoped>
.page-subtitle {
  margin-top: 6px;
  color: #5b667a;
  font-size: 13px;
}

.settings-grid {
  display: grid;
  grid-template-columns: minmax(280px, 0.8fr) minmax(360px, 1.2fr);
  gap: 16px;
}

.settings-panel {
  background: #fff;
  border: 1px solid #e6eaf2;
  border-radius: 8px;
  padding: 16px;
}

.section-title {
  margin-bottom: 14px;
  color: #172033;
  font-size: 15px;
  font-weight: 650;
}

.compact-grid {
  display: grid;
  grid-template-columns: 220px 1fr;
  gap: 16px;
  align-items: center;
}

.connection-panel {
  margin-top: 16px;
}

.result-block {
  margin-top: 16px;
}

@media (max-width: 900px) {
  .settings-grid,
  .compact-grid {
    grid-template-columns: 1fr;
  }
}
</style>
