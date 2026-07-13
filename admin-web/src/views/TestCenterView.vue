<template>
  <section class="page test-center">
    <div class="page-header">
      <h1 class="page-title">测试中心</h1>
      <a-button @click="refreshAll">刷新配置</a-button>
    </div>

    <a-tabs v-model:active-key="activeTab">
      <a-tab-pane key="health" tab="配置健康检查">
        <a-alert class="section-alert" type="info" show-icon message="敏感配置只显示是否已配置，不展示密钥或 token 明文。" />
        <a-button type="primary" :loading="loading.health" @click="loadHealth">运行检查</a-button>
        <a-descriptions v-if="health" class="result-block" bordered :column="2" size="small">
          <a-descriptions-item v-for="item in healthItems" :key="item.label" :label="item.label">
            <a-tag :color="item.ok ? 'green' : 'red'">{{ item.value }}</a-tag>
          </a-descriptions-item>
        </a-descriptions>
        <ResultViewer :data="health" />
      </a-tab-pane>

      <a-tab-pane key="deepseek" tab="DeepSeek 测试">
        <div class="connection-panel">
          <a-alert class="section-alert" type="info" show-icon message="连接测试会直接调用 DeepSeek chat/completions，用于验证 .env 中的 API Key、模型名和网络连通性。" />
          <a-form layout="vertical">
            <a-form-item label="连接测试提示词">
              <a-input v-model:value="deepseek.connectionPrompt" />
            </a-form-item>
            <a-button type="primary" :loading="loading.deepseekConnection" @click="runDeepSeekConnection">测试 DeepSeek 连接</a-button>
          </a-form>
          <a-descriptions v-if="deepseek.connectionResult" class="result-block" bordered :column="2" size="small">
            <a-descriptions-item label="状态">
              <a-tag :color="connectionOk ? 'green' : 'red'">{{ connectionOk ? '连接成功' : '连接失败' }}</a-tag>
            </a-descriptions-item>
            <a-descriptions-item label="模型">
              {{ deepseek.connectionResult.model || '-' }}
            </a-descriptions-item>
            <a-descriptions-item label="耗时">
              {{ deepseek.connectionResult.latencyMs ?? '-' }} ms
            </a-descriptions-item>
            <a-descriptions-item label="返回">
              {{ deepseek.connectionResult.answer || deepseek.connectionResult.error || '-' }}
            </a-descriptions-item>
          </a-descriptions>
          <ResultViewer :data="deepseek.connectionResult" />
        </div>
      </a-tab-pane>

      <a-tab-pane key="mcp" tab="MCP 测试">
        <div class="split-grid">
          <a-form layout="vertical">
            <a-alert class="section-alert" type="info" show-icon message="这里仅保留底层 MCP 工具发现与连通性诊断；自然语言查询请使用 Agent 端到端测试。" />
            <a-form-item label="项目 Token">
              <a-select
                v-model:value="mcp.tokenId"
                :options="tokenOptions"
                show-search
                option-filter-prop="label"
                placeholder="选择后台已保存的 token"
              />
            </a-form-item>
            <div class="toolbar">
              <a-button type="primary" :loading="loading.mcpTools" @click="loadMcpTools">加载 MCP 工具</a-button>
            </div>
          </a-form>
          <div>
            <a-descriptions v-if="mcp.result" class="result-block mcp-summary" bordered :column="2" size="small">
              <a-descriptions-item label="状态">
                <a-tag :color="mcpOk ? 'green' : 'red'">{{ mcpOk ? '调用成功' : '调用失败' }}</a-tag>
              </a-descriptions-item>
              <a-descriptions-item label="工具数量">
                {{ mcp.result.toolCount ?? '-' }}
              </a-descriptions-item>
              <a-descriptions-item label="结果" :span="2">
                {{ mcp.result.error || '已读取实时工具列表' }}
              </a-descriptions-item>
            </a-descriptions>
            <ResultViewer :data="mcp.result" />
          </div>
        </div>
      </a-tab-pane>

      <a-tab-pane key="agent" tab="Agent 端到端测试">
        <div class="split-grid">
          <a-form layout="vertical">
            <a-form-item label="飞书 open_id">
              <a-input v-model:value="agent.feishuOpenId" />
            </a-form-item>
            <a-form-item label="飞书 chat_id">
              <a-input v-model:value="agent.feishuChatId" />
            </a-form-item>
            <a-form-item label="会话类型">
              <a-radio-group v-model:value="agent.chatType">
                <a-radio-button value="p2p">私聊</a-radio-button>
                <a-radio-button value="group">群聊</a-radio-button>
              </a-radio-group>
            </a-form-item>
            <a-form-item label="消息">
              <a-textarea v-model:value="agent.message" :rows="5" />
            </a-form-item>
            <a-alert class="section-alert" type="warning" show-icon message="端到端测试默认不向飞书发送消息，只在页面返回调试结果。" />
            <a-button type="primary" :loading="loading.agent" @click="runAgentQuery">运行 Agent 查询</a-button>
          </a-form>
          <div>
            <a-descriptions v-if="agentSummaryVisible" class="result-block mcp-summary" bordered :column="2" size="small">
              <a-descriptions-item label="路径">
                {{ agentPath }}
              </a-descriptions-item>
              <a-descriptions-item label="模型">
                {{ agentModel }}
              </a-descriptions-item>
              <a-descriptions-item label="MCP 轮次">
                {{ agentToolRounds }}
              </a-descriptions-item>
              <a-descriptions-item label="工具调用数">
                {{ agentToolCallCount }}
              </a-descriptions-item>
              <a-descriptions-item label="最终回答" :span="2">
                <pre class="inline-result">{{ agentFinalAnswer }}</pre>
              </a-descriptions-item>
            </a-descriptions>
            <ResultViewer :data="agent.result" />
          </div>
        </div>
      </a-tab-pane>

      <a-tab-pane key="feishu" tab="飞书事件测试">
        <div class="split-grid">
          <a-form layout="vertical">
            <a-alert class="section-alert" type="info" show-icon message="先检查飞书 Token。如果 Token 正常，再去飞书客户端发消息，并观察后端控制台是否出现 Received Feishu event 日志。" />
            <div class="toolbar">
              <a-button :loading="loading.feishuToken" @click="checkFeishuToken">检查飞书 Token</a-button>
            </div>
            <a-form-item label="飞书事件 JSON">
              <a-textarea v-model:value="feishu.eventJson" :rows="18" />
            </a-form-item>
            <a-button type="primary" :loading="loading.feishu" @click="runFeishuMock">模拟事件</a-button>
          </a-form>
          <div>
            <a-descriptions v-if="feishu.tokenResult" class="result-block mcp-summary" bordered :column="2" size="small">
              <a-descriptions-item label="Token 状态">
                <a-tag :color="feishuTokenOk ? 'green' : 'red'">{{ feishuTokenOk ? '正常' : '异常' }}</a-tag>
              </a-descriptions-item>
              <a-descriptions-item label="Token">
                {{ feishu.tokenResult.tokenPrefix || feishu.tokenResult.error || '-' }}
              </a-descriptions-item>
            </a-descriptions>
            <ResultViewer :data="feishu.result" />
          </div>
        </div>
      </a-tab-pane>

      <a-tab-pane key="feishu-card" tab="飞书卡片测试">
        <div class="split-grid">
          <a-form layout="vertical">
            <a-alert class="section-alert" type="info" show-icon message="选择人员时会使用人员配置中的飞书 open_id 批量发送；不选择人员时，可继续手动输入 open_id 或 chat_id 发送。" />
            <div class="preset-panel">
              <div class="preset-panel-header">
                <div>
                  <div class="preset-title">卡片模板</div>
                  <div class="preset-subtitle">{{ selectedPreset?.description }}</div>
                </div>
                <a-select
                  v-model:value="feishuCard.presetKey"
                  class="preset-select"
                  :options="cardPresetOptions"
                  :loading="loading.feishuCardPresets"
                  :disabled="loading.feishuCardPresets || cardPresets.length === 0"
                  @change="applyCardPreset"
                />
              </div>
              <div class="preset-grid">
                <button
                  v-for="preset in cardPresets"
                  :key="preset.key"
                  type="button"
                  class="preset-card"
                  :class="{ active: feishuCard.presetKey === preset.key }"
                  :disabled="loading.feishuCardPresets"
                  @click="selectCardPreset(preset.key)"
                >
                  <span class="preset-card-tag" :style="{ background: preset.color }"></span>
                  <span class="preset-card-name">{{ preset.label }}</span>
                  <span class="preset-card-desc">{{ preset.description }}</span>
                </button>
              </div>
            </div>
            <a-alert
              v-if="!loading.feishuCardPresets && cardPresets.length === 0"
              class="section-alert"
              type="error"
              show-icon
              message="未加载到卡片模板，请检查后端调试接口。"
            />
            <a-form-item label="选择人员（支持多选）">
              <a-select
                v-model:value="feishuCard.selectedOpenIds"
                mode="multiple"
                :options="personOptions"
                allow-clear
                show-search
                option-filter-prop="label"
                placeholder="从人员配置中选择接收人"
              />
            </a-form-item>
            <a-form-item label="接收 ID 类型">
              <a-select v-model:value="feishuCard.receiveIdType" :options="receiveIdTypeOptions" />
            </a-form-item>
            <a-form-item label="接收 ID">
              <a-input v-model:value="feishuCard.receiveId" placeholder="例如 open_id、user_id、email 或 chat_id" />
            </a-form-item>
            <a-form-item label="卡片 JSON">
              <a-textarea v-model:value="feishuCard.cardJson" :rows="18" />
            </a-form-item>
            <div class="toolbar">
              <a-button type="primary" :loading="loading.feishuCard" :disabled="!feishuCard.cardJson" @click="sendFeishuCard">发送卡片</a-button>
              <a-button :disabled="!feishuCard.cardJson" @click="formatFeishuCardJson">格式化 JSON</a-button>
              <a-button :disabled="!selectedPreset" @click="resetFeishuCard">恢复当前模板</a-button>
            </div>
          </a-form>
          <div>
            <a-descriptions v-if="feishuCard.result" class="result-block mcp-summary" bordered :column="2" size="small">
              <a-descriptions-item label="发送状态">
                <a-tag :color="feishuCardOk ? 'green' : 'red'">{{ feishuCardOk ? '成功' : '失败' }}</a-tag>
              </a-descriptions-item>
              <a-descriptions-item label="接收类型">
                {{ feishuCard.result.receiveIdType || '-' }}
              </a-descriptions-item>
              <a-descriptions-item label="接收 ID" :span="2">
                {{ feishuCard.result.receiveId || formatReceiveIds(feishuCard.result.receiveIds) }}
              </a-descriptions-item>
              <a-descriptions-item label="批量结果" :span="2">
                {{ formatBatchSummary(feishuCard.result) }}
              </a-descriptions-item>
              <a-descriptions-item label="错误" :span="2">
                {{ feishuCard.result.error || '-' }}
              </a-descriptions-item>
            </a-descriptions>
            <ResultViewer :data="feishuCard.result" />
          </div>
        </div>
      </a-tab-pane>
    </a-tabs>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref } from 'vue'
import { message } from 'ant-design-vue'
import { adminApi, type FeishuCardPreset } from '../api/admin'
import ResultViewer from '../components/ResultViewer.vue'

const activeTab = ref('health')
const health = ref<Record<string, unknown> | null>(null)
const tokenOptions = ref<{ label: string; value: number }[]>([])
const personOptions = ref<{ label: string; value: string }[]>([])

const loading = reactive({
  health: false,
  deepseekConnection: false,
  mcpTools: false,
  agent: false,
  feishuToken: false,
  feishu: false,
  feishuCard: false,
  feishuCardPresets: false
})

const receiveIdTypeOptions = [
  { label: 'open_id', value: 'open_id' },
  { label: 'user_id', value: 'user_id' },
  { label: 'union_id', value: 'union_id' },
  { label: 'email', value: 'email' },
  { label: 'chat_id', value: 'chat_id' }
]

const deepseek = reactive({
  connectionPrompt: '请只回复 OK，用于测试 DeepSeek API 连通性。',
  connectionResult: null as Record<string, unknown> | null
})

const mcp = reactive({
  tokenId: undefined as number | undefined,
  result: null as Record<string, unknown> | null
})

const agent = reactive({
  feishuOpenId: '',
  feishuChatId: 'debug-chat',
  chatType: 'p2p',
  message: '帮我看一下 Roche 项目的风险',
  result: null as unknown
})

const feishu = reactive({
  tokenResult: null as Record<string, unknown> | null,
  eventJson: JSON.stringify({
    event: {
      sender: { sender_id: { open_id: 'ou_debug' } },
      message: {
        message_id: `debug_${Date.now()}`,
        chat_id: 'oc_debug',
        chat_type: 'p2p',
        message_type: 'text',
        content: JSON.stringify({ text: '帮我看一下 Roche 项目的风险' })
      }
    }
  }, null, 2),
  result: null as unknown
})

const cardPresets = ref<FeishuCardPreset[]>([])

const cardPresetOptions = computed(() => cardPresets.value.map((preset) => ({
  label: preset.label,
  value: preset.key
})))

const defaultPresetKey = 'primelayer-answer'

const feishuCard = reactive({
  receiveIdType: 'open_id',
  receiveId: '',
  selectedOpenIds: [] as string[],
  presetKey: defaultPresetKey,
  cardJson: '',
  result: null as Record<string, unknown> | null
})

const healthItems = computed(() => {
  const value = health.value || {}
  const mysql = value.mysql as Record<string, unknown> | undefined
  const rabbit = value.rabbitmq as Record<string, unknown> | undefined
  return [
    { label: 'MySQL', ok: mysql?.ok === true, value: mysql?.ok === true ? '正常' : '异常' },
    { label: 'RabbitMQ', ok: rabbit?.ok === true, value: rabbit?.ok === true ? '正常' : '异常' },
    { label: 'DeepSeek Key', ok: value.deepSeekApiKeyConfigured === true, value: value.deepSeekApiKeyConfigured ? '已配置' : '未配置' },
    { label: 'MCP Endpoint', ok: value.mcpEndpointConfigured === true, value: value.mcpEndpointConfigured ? '已配置' : '未配置' },
    { label: 'DeepSeek 模型', ok: Boolean(value.deepSeekModel), value: String(value.deepSeekModel || '-') },
    { label: '飞书 App ID', ok: value.feishuAppIdConfigured === true, value: value.feishuAppIdConfigured ? '已配置' : '未配置' },
    { label: '飞书 Secret', ok: value.feishuAppSecretConfigured === true, value: value.feishuAppSecretConfigured ? '已配置' : '未配置' },
    { label: '飞书 Verification Token', ok: value.feishuVerificationTokenConfigured === true, value: value.feishuVerificationTokenConfigured ? '已配置' : '未配置' },
    { label: '飞书 Echo 模式', ok: value.feishuEchoEnabled === true, value: value.feishuEchoEnabled ? '已开启' : '已关闭' }
  ]
})

const connectionOk = computed(() => deepseek.connectionResult?.ok === true)
const mcpOk = computed(() => mcp.result?.ok === true)
const feishuTokenOk = computed(() => feishu.tokenResult?.ok === true)
const feishuCardOk = computed(() => feishuCard.result?.ok === true)
const selectedPreset = computed(() => getCardPreset(feishuCard.presetKey))
const agentResult = computed(() => (agent.result || {}) as Record<string, unknown>)
const agentSummaryVisible = computed(() => Boolean(agent.result))
const agentPath = computed(() => String(agentResult.value.path || '-'))
const agentModel = computed(() => String(agentResult.value.model || '-'))
const agentToolRounds = computed(() => Number(agentResult.value.toolRounds || 0))
const agentToolCallCount = computed(() => Number(agentResult.value.logicalToolCalls || 0))
const agentFinalAnswer = computed(() => String(agentResult.value.finalAnswer || '-'))

async function refreshAll() {
  await Promise.all([loadHealth(), loadTokens(), loadPeople(), loadFeishuCardPresets()])
}

async function loadHealth() {
  loading.health = true
  try {
    health.value = await adminApi.debugHealth()
  } catch (error) {
    showError(error)
  } finally {
    loading.health = false
  }
}

async function loadTokens() {
  try {
    const rows = await adminApi.listProjectTokens()
    tokenOptions.value = rows.map((row) => ({
      value: Number(row.id),
      label: `${row.project_name || '-'} / ${row.project_id || '-'} / ${row.primelayer_user_id || '-'} / ${row.token_hash_suffix || '-'}`
    }))
    if (!mcp.tokenId && tokenOptions.value.length > 0) {
      mcp.tokenId = tokenOptions.value[0].value
    }
  } catch (error) {
    showError(error)
  }
}

async function loadPeople() {
  try {
    const rows = await adminApi.listUserBindings()
    personOptions.value = rows
      .map((row) => ({
        value: String(row.feishu_open_id || ''),
        label: `${row.person_name || row.primelayer_user_name || row.primelayer_user_id || '-'} / ${row.feishu_open_id || '-'}`
      }))
      .filter((option) => option.value)
  } catch (error) {
    showError(error)
  }
}

async function loadFeishuCardPresets() {
  loading.feishuCardPresets = true
  try {
    const presets = await adminApi.debugFeishuCardPresets()
    if (!Array.isArray(presets) || presets.length === 0) {
      throw new Error('后端未返回可用的飞书卡片模板')
    }
    cardPresets.value = presets
    if (!presets.some((preset) => preset.key === feishuCard.presetKey)) {
      feishuCard.presetKey = presets[0].key
    }
    applyCardPreset()
  } catch (error) {
    cardPresets.value = []
    feishuCard.cardJson = ''
    showError(error)
  } finally {
    loading.feishuCardPresets = false
  }
}

async function runDeepSeekConnection() {
  loading.deepseekConnection = true
  try {
    deepseek.connectionResult = await adminApi.debugDeepSeekConnection({
      prompt: deepseek.connectionPrompt
    })
  } catch (error) {
    showError(error)
  } finally {
    loading.deepseekConnection = false
  }
}

async function loadMcpTools() {
  if (!mcp.tokenId) {
    message.warning('请先选择项目 Token')
    return
  }
  loading.mcpTools = true
  try {
    mcp.result = await adminApi.debugMcpTools({
      tokenId: mcp.tokenId
    })
  } catch (error) {
    showError(error)
  } finally {
    loading.mcpTools = false
  }
}

async function runAgentQuery() {
  loading.agent = true
  try {
    agent.result = await adminApi.debugAgentQuery({
      feishuOpenId: agent.feishuOpenId,
      feishuChatId: agent.feishuChatId,
      chatType: agent.chatType,
      message: agent.message,
      sendFeishuMessage: false
    })
  } catch (error) {
    showError(error)
  } finally {
    loading.agent = false
  }
}

async function runFeishuMock() {
  loading.feishu = true
  try {
    feishu.result = await adminApi.debugFeishuMockEvent({
      event: parseJson(feishu.eventJson)
    })
  } catch (error) {
    showError(error)
  } finally {
    loading.feishu = false
  }
}

async function checkFeishuToken() {
  loading.feishuToken = true
  try {
    feishu.tokenResult = await adminApi.debugFeishuToken()
  } catch (error) {
    showError(error)
  } finally {
    loading.feishuToken = false
  }
}

function selectCardPreset(key: string) {
  feishuCard.presetKey = key
  applyCardPreset()
}

function applyCardPreset() {
  feishuCard.cardJson = selectedPreset.value ? stringifyCard(selectedPreset.value.card) : ''
}

function formatFeishuCardJson() {
  try {
    feishuCard.cardJson = stringifyCard(parseJson(feishuCard.cardJson))
    message.success('JSON 已格式化')
  } catch (error) {
    showError(error)
  }
}

async function sendFeishuCard() {
  let card: unknown
  try {
    card = parseJson(feishuCard.cardJson)
  } catch (error) {
    showError(error)
    return
  }
  if (feishuCard.selectedOpenIds.length > 0) {
    loading.feishuCard = true
    try {
      feishuCard.result = await adminApi.debugFeishuCardBatch({
        receiveIdType: 'open_id',
        receiveIds: feishuCard.selectedOpenIds,
        card
      })
    } catch (error) {
      showError(error)
    } finally {
      loading.feishuCard = false
    }
    return
  }
  if (!feishuCard.receiveId.trim()) {
    message.warning('请输入接收 ID')
    return
  }
  loading.feishuCard = true
  try {
    feishuCard.result = await adminApi.debugFeishuCard({
      receiveIdType: feishuCard.receiveIdType,
      receiveId: feishuCard.receiveId.trim(),
      card
    })
  } catch (error) {
    showError(error)
  } finally {
    loading.feishuCard = false
  }
}

function resetFeishuCard() {
  applyCardPreset()
}

function getCardPreset(key: string) {
  return cardPresets.value.find((preset) => preset.key === key) || cardPresets.value[0]
}

function stringifyCard(card: unknown) {
  return JSON.stringify(card, null, 2)
}

function formatReceiveIds(value: unknown) {
  return Array.isArray(value) ? value.join(', ') : '-'
}

function formatBatchSummary(value: Record<string, unknown>) {
  if (value.total == null) {
    return '-'
  }
  return `共 ${value.total} 个，成功 ${value.succeeded ?? 0} 个，失败 ${value.failed ?? 0} 个`
}

function parseJson(input: string) {
  try {
    return JSON.parse(input)
  } catch {
    throw new Error('JSON 格式不正确')
  }
}

function showError(error: unknown) {
  message.error(error instanceof Error ? error.message : '请求失败')
}

onMounted(refreshAll)
</script>

<style scoped>
.test-center :deep(.ant-tabs-nav) {
  margin-bottom: 16px;
}

.split-grid {
  display: grid;
  grid-template-columns: minmax(320px, 480px) minmax(0, 1fr);
  gap: 16px;
  align-items: start;
}

.connection-panel {
  margin-bottom: 20px;
  padding-bottom: 20px;
  border-bottom: 1px solid #edf0f4;
}

.section-alert {
  margin-bottom: 16px;
}

.preset-panel {
  margin-bottom: 16px;
  padding: 14px;
  background: #f8fafc;
  border: 1px solid #e6eaf2;
  border-radius: 8px;
}

.preset-panel-header {
  display: flex;
  gap: 12px;
  align-items: flex-start;
  justify-content: space-between;
  margin-bottom: 12px;
}

.preset-title {
  font-size: 15px;
  font-weight: 700;
  color: #1f2937;
}

.preset-subtitle {
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.5;
  color: #667085;
}

.preset-select {
  width: 180px;
  flex: 0 0 auto;
}

.preset-grid {
  display: grid;
  grid-template-columns: repeat(2, minmax(0, 1fr));
  gap: 8px;
}

.preset-card {
  min-height: 78px;
  padding: 10px 12px;
  background: #fff;
  border: 1px solid #d9e0ec;
  border-radius: 8px;
  text-align: left;
  cursor: pointer;
  transition: border-color 0.16s ease, box-shadow 0.16s ease, transform 0.16s ease;
}

.preset-card:hover,
.preset-card.active {
  border-color: #1677ff;
  box-shadow: 0 6px 18px rgb(16 24 40 / 10%);
}

.preset-card:hover {
  transform: translateY(-1px);
}

.preset-card-tag {
  display: inline-block;
  width: 28px;
  height: 4px;
  margin-bottom: 8px;
  border-radius: 999px;
}

.preset-card-name {
  display: block;
  font-size: 13px;
  font-weight: 700;
  color: #1f2937;
}

.preset-card-desc {
  display: block;
  margin-top: 4px;
  font-size: 12px;
  line-height: 1.45;
  color: #667085;
}

.result-block {
  margin-top: 16px;
}

.mcp-summary {
  margin-top: 0;
  margin-bottom: 12px;
}

.inline-result {
  max-height: 220px;
  margin: 0;
  overflow: auto;
  white-space: pre-wrap;
  word-break: break-word;
  font-size: 12px;
  line-height: 1.6;
}

@media (max-width: 960px) {
  .split-grid {
    grid-template-columns: 1fr;
  }

  .preset-panel-header {
    display: block;
  }

  .preset-select {
    width: 100%;
    margin-top: 10px;
  }

  .preset-grid {
    grid-template-columns: 1fr;
  }
}
</style>
