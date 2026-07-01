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
        <div class="split-grid">
          <a-form layout="vertical">
            <a-form-item label="问题">
              <a-textarea v-model:value="deepseek.question" :rows="4" />
            </a-form-item>
            <a-form-item label="会话类型">
              <a-radio-group v-model:value="deepseek.chatType">
                <a-radio-button value="p2p">私聊</a-radio-button>
                <a-radio-button value="group">群聊</a-radio-button>
              </a-radio-group>
            </a-form-item>
            <div class="toolbar">
              <a-button type="primary" :loading="loading.plan" @click="runDeepSeekPlan">生成计划</a-button>
              <a-button :loading="loading.summarize" @click="runDeepSeekSummarize">总结模拟数据</a-button>
            </div>
            <a-form-item label="模拟 MCP 数据 JSON">
              <a-textarea v-model:value="deepseek.toolResultsJson" :rows="8" />
            </a-form-item>
          </a-form>
          <ResultViewer :data="deepseek.result" />
        </div>
      </a-tab-pane>

      <a-tab-pane key="mcp" tab="MCP 测试">
        <div class="split-grid">
          <a-form layout="vertical">
            <a-alert class="section-alert" type="info" show-icon message="MCP 测试现在使用自然语言问题。后端会先读取 Primelayer MCP 的真实工具列表，再用 DeepSeek 选择工具并生成参数。" />
            <a-form-item label="项目 Token">
              <a-select
                v-model:value="mcp.tokenId"
                :options="tokenOptions"
                show-search
                option-filter-prop="label"
                placeholder="选择后台已保存的 token"
              />
            </a-form-item>
            <a-form-item label="问题">
              <a-textarea v-model:value="mcp.question" :rows="6" />
            </a-form-item>
            <div class="toolbar">
              <a-button type="primary" :loading="loading.mcp" @click="runMcpQuestion">用问题调用 MCP</a-button>
              <a-button :loading="loading.mcpTools" @click="loadMcpTools">加载 MCP 工具</a-button>
            </div>
          </a-form>
          <div>
            <a-descriptions v-if="mcp.result" class="result-block mcp-summary" bordered :column="2" size="small">
              <a-descriptions-item label="状态">
                <a-tag :color="mcpOk ? 'green' : 'red'">{{ mcpOk ? '调用成功' : '调用失败' }}</a-tag>
              </a-descriptions-item>
              <a-descriptions-item label="耗时">
                {{ mcp.result.latencyMs ?? '-' }} ms
              </a-descriptions-item>
              <a-descriptions-item label="工具">
                {{ mcp.result.toolName || '-' }}
              </a-descriptions-item>
              <a-descriptions-item label="规划器">
                {{ mcp.result.planner || '-' }}
              </a-descriptions-item>
              <a-descriptions-item label="摘要" :span="2">
                {{ mcp.result.summary || mcp.result.error || '-' }}
              </a-descriptions-item>
              <a-descriptions-item label="选择原因" :span="2">
                {{ mcp.result.reason || '-' }}
              </a-descriptions-item>
              <a-descriptions-item label="返回文本" :span="2">
                <pre class="inline-result">{{ mcp.result.resultText || '-' }}</pre>
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
              <a-descriptions-item label="规划器">
                {{ agentPlanner }}
              </a-descriptions-item>
              <a-descriptions-item label="Skill">
                {{ agentSkill }}
              </a-descriptions-item>
              <a-descriptions-item label="项目范围">
                {{ agentProjectScope }}
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
                  @click="selectCardPreset(preset.key)"
                >
                  <span class="preset-card-tag" :style="{ background: preset.color }"></span>
                  <span class="preset-card-name">{{ preset.label }}</span>
                  <span class="preset-card-desc">{{ preset.description }}</span>
                </button>
              </div>
            </div>
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
              <a-button type="primary" :loading="loading.feishuCard" @click="sendFeishuCard">发送卡片</a-button>
              <a-button @click="formatFeishuCardJson">格式化 JSON</a-button>
              <a-button @click="resetFeishuCard">恢复当前模板</a-button>
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
import { adminApi } from '../api/admin'
import ResultViewer from '../components/ResultViewer.vue'
import { formatChinaDate, formatChinaDateTime } from '../utils/time'

const activeTab = ref('health')
const health = ref<Record<string, unknown> | null>(null)
const tokenOptions = ref<{ label: string; value: number }[]>([])
const personOptions = ref<{ label: string; value: string }[]>([])

const loading = reactive({
  health: false,
  deepseekConnection: false,
  plan: false,
  summarize: false,
  mcp: false,
  mcpTools: false,
  agent: false,
  feishuToken: false,
  feishu: false,
  feishuCard: false
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
  connectionResult: null as Record<string, unknown> | null,
  question: '帮我看一下 Roche 项目的风险',
  chatType: 'p2p',
  toolResultsJson: JSON.stringify([
    { projectId: 'roche', status: 'SUCCEEDED', result: { risks: ['示例风险'] } }
  ], null, 2),
  result: null as unknown
})

const mcp = reactive({
  tokenId: undefined as number | undefined,
  question: '获取当前项目名称、工作空间名称、用户个人信息和租户名称',
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

type CardPreset = {
  key: string
  label: string
  description: string
  color: string
  create: () => Record<string, unknown>
}

const cardPresets: CardPreset[] = [
  {
    key: 'primelayer-answer',
    label: 'AI 回答卡片',
    description: '飞书正式回答样式，预留图标、指标和 ECharts 图表数据位',
    color: '#1455d9',
    create: createPrimelayerAnswerCard
  },
  {
    key: 'basic-test',
    label: '基础测试卡片',
    description: '验证标题、字段、分割线和按钮渲染',
    color: '#1677ff',
    create: createBasicTestCard
  },
  {
    key: 'daily-todo',
    label: '每日待办',
    description: '适合每天上午推送个人/项目待办清单',
    color: '#22a06b',
    create: createDailyTodoCard
  },
  {
    key: 'construction-daily',
    label: '施工日报',
    description: '适合项目群每日同步进度、安全、质量和明日计划',
    color: '#d97008',
    create: createConstructionDailyCard
  },
  {
    key: 'risk-alert',
    label: '风险提醒',
    description: '突出展示高优先级风险和建议动作',
    color: '#d92d20',
    create: createRiskAlertCard
  },
  {
    key: 'weekly-summary',
    label: '周报摘要',
    description: '汇总本周进展、阻塞项和下周重点',
    color: '#6941c6',
    create: createWeeklySummaryCard
  }
]

const cardPresetOptions = cardPresets.map((preset) => ({
  label: preset.label,
  value: preset.key
}))

const defaultPresetKey = 'primelayer-answer'
const defaultCardJson = stringifyCard(getCardPreset(defaultPresetKey).create())

const feishuCard = reactive({
  receiveIdType: 'open_id',
  receiveId: '',
  selectedOpenIds: [] as string[],
  presetKey: defaultPresetKey,
  cardJson: defaultCardJson,
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
    { label: 'MCP Header', ok: Boolean(value.mcpAuthHeaderName), value: String(value.mcpAuthHeaderName || '-') },
    { label: 'AI 引擎', ok: Boolean(value.aiEngine), value: String(value.aiEngine || '-') },
    { label: 'Agent Service', ok: value.agentServiceEnabled === true, value: value.agentServiceEnabled ? '已启用' : '未启用' },
    { label: 'FastGPT Key', ok: value.fastGptApiKeyConfigured === true, value: value.fastGptApiKeyConfigured ? '已配置' : '未配置' },
    { label: 'FastGPT 记忆', ok: value.fastGptMemoryEnabled === true, value: value.fastGptMemoryEnabled ? '已开启' : '已关闭' },
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
const agentPlan = computed(() => {
  const result = agentResult.value
  return (result.agentPlan || result.plan || {}) as Record<string, unknown>
})
const agentSummaryVisible = computed(() => Boolean(agent.result))
const agentPlanner = computed(() => String(agentResult.value.planner || (agentResult.value.agentServiceFallback ? 'legacy-fallback' : 'legacy-deepseek')))
const agentSkill = computed(() => String(agentPlan.value.skillId || agentPlan.value.intent || '-'))
const agentProjectScope = computed(() => String(agentPlan.value.projectScope || '-'))
const agentToolCallCount = computed(() => {
  const calls = agentPlan.value.toolCalls
  return Array.isArray(calls) ? calls.length : 0
})
const agentFinalAnswer = computed(() => String(agentResult.value.finalAnswer || '-'))

async function refreshAll() {
  await Promise.all([loadHealth(), loadTokens(), loadPeople()])
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

async function runDeepSeekPlan() {
  loading.plan = true
  try {
    deepseek.result = await adminApi.debugDeepSeekPlan({
      question: deepseek.question,
      chatType: deepseek.chatType
    })
  } catch (error) {
    showError(error)
  } finally {
    loading.plan = false
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

async function runDeepSeekSummarize() {
  loading.summarize = true
  try {
    deepseek.result = await adminApi.debugDeepSeekSummarize({
      question: deepseek.question,
      toolResults: parseJson(deepseek.toolResultsJson)
    })
  } catch (error) {
    showError(error)
  } finally {
    loading.summarize = false
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

async function runMcpQuestion() {
  if (!mcp.tokenId) {
    message.warning('请先选择项目 Token')
    return
  }
  if (!mcp.question.trim()) {
    message.warning('请输入问题')
    return
  }
  loading.mcp = true
  try {
    mcp.result = await adminApi.debugMcpQuestion({
      tokenId: mcp.tokenId,
      question: mcp.question
    })
  } catch (error) {
    showError(error)
  } finally {
    loading.mcp = false
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
  feishuCard.cardJson = stringifyCard(selectedPreset.value.create())
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
  return cardPresets.find((preset) => preset.key === key) || cardPresets[0]
}

function stringifyCard(card: unknown) {
  return JSON.stringify(card, null, 2)
}

function currentDateLabel() {
  return formatChinaDate()
}

function currentDateTimeLabel() {
  return formatChinaDateTime(new Date())
}

function textBlock(content: string) {
  return {
    tag: 'div',
    text: {
      tag: 'lark_md',
      content
    }
  }
}

function fieldBlock(fields: string[]) {
  return {
    tag: 'div',
    fields: fields.map((content) => ({
      is_short: true,
      text: {
        tag: 'lark_md',
        content
      }
    }))
  }
}

function noteBlock(content: string) {
  return {
    tag: 'note',
    elements: [
      {
        tag: 'plain_text',
        content
      }
    ]
  }
}

function buttonAction(label: string, action: string, type = 'default', extraValue: Record<string, unknown> = {}) {
  return {
    tag: 'button',
    text: {
      tag: 'plain_text',
      content: label
    },
    type,
    value: {
      action,
      ...extraValue
    }
  }
}

function createPrimelayerAnswerCard() {
  const chartOption = {
    title: { text: '任务状态分布' },
    tooltip: { trigger: 'item' },
    legend: { bottom: 0 },
    series: [
      {
        type: 'pie',
        radius: ['45%', '70%'],
        data: [
          { name: '已完成', value: 18 },
          { name: '进行中', value: 7 },
          { name: '有风险', value: 3 }
        ]
      }
    ]
  }
  return {
    config: {
      wide_screen_mode: true,
      enable_forward: true
    },
    header: {
      title: {
        tag: 'plain_text',
        content: 'Primelayer AI 回答'
      },
      template: 'blue'
    },
    elements: [
      fieldBlock([
        '**来源**\nPrimelayer AI',
        '**意图**\n项目查询',
        '**数据范围**\nRoche 项目',
        `**生成时间**\n${currentDateTimeLabel()}`
      ]),
      {
        tag: 'hr'
      },
      textBlock('**问题**\n今天 Roche 项目施工情况如何？'),
      {
        tag: 'hr'
      },
      textBlock('**回答**\n今天项目整体推进正常，主要完成了 2F 弱电桥架安装和 3F 隔墙龙骨复核。当前需要关注空调末端设备到场延迟，以及 5 个机电点位尚未确认的问题。'),
      fieldBlock([
        '**项目状态**\n正常推进',
        '**完成事项**\n18 项',
        '**风险事项**\n3 项',
        '**建议优先级**\n先处理材料到场'
      ]),
      {
        tag: 'hr'
      },
      textBlock('**图表数据预设**\n任务状态分布：已完成 18 项，进行中 7 项，有风险 3 项。后续可将 `chartSpec.option` 渲染为 ECharts 图片或替换为飞书原生图表组件。'),
      {
        tag: 'action',
        actions: [
          buttonAction('查看项目详情', 'open_project_detail', 'primary', {
            iconKey: 'project',
            projectId: 'demo-roche'
          }),
          buttonAction('查看图表', 'open_chart_detail', 'default', {
            iconKey: 'chart',
            chartSpec: {
              component: 'echarts',
              version: 1,
              renderMode: 'image_or_native',
              option: chartOption
            }
          })
        ]
      },
      noteBlock('由 Primelayer AI 生成 · primelayer-ai-card/v1')
    ]
  }
}

function createBasicTestCard() {
  return {
    config: {
      wide_screen_mode: true
    },
    header: {
      title: {
        tag: 'plain_text',
        content: 'Primelayer Agent 测试卡片'
      },
      template: 'blue'
    },
    elements: [
      textBlock('**测试说明**\n这是一条由 Lark Connect Agent Admin 发送的飞书交互卡片，用于验证应用能否向指定接收人发送 `interactive` 消息。'),
      fieldBlock([
        '**接收对象**\n以页面选择人员或手动输入的 receive_id 为准',
        `**发送时间**\n${currentDateTimeLabel()}`
      ]),
      {
        tag: 'action',
        actions: [
          buttonAction('按钮渲染测试', 'debug_card_button', 'primary')
        ]
      },
      {
        tag: 'hr'
      },
      textBlock('**预期结果**\n如果你能看到标题、字段和按钮，说明飞书卡片消息发送链路已跑通。'),
      {
        tag: 'hr'
      },
      noteBlock('按钮仅用于渲染测试，暂未接入点击回调')
    ]
  }
}

function createDailyTodoCard() {
  return {
    config: {
      wide_screen_mode: true
    },
    header: {
      title: {
        tag: 'plain_text',
        content: '今日待办提醒'
      },
      template: 'green'
    },
    elements: [
      textBlock(`**${currentDateLabel()}｜Primelayer Agent 自动汇总**\n以下是今天建议优先处理的事项，请根据实际项目状态调整负责人和截止时间。`),
      fieldBlock([
        '**项目**\nRoche 项目',
        '**待办总数**\n8 项',
        '**高优先级**\n2 项',
        '**生成时间**\n' + currentDateTimeLabel()
      ]),
      {
        tag: 'hr'
      },
      textBlock('**优先事项**\n1. [高] 跟进 3F 机电洞口封堵确认，截止 12:00\n2. [中] 确认材料到场计划，避免影响明日吊顶施工\n3. [低] 更新本周验收清单，并同步项目群\n4. [低] 整理昨日遗留问题照片，补充责任人'),
      textBlock('**建议节奏**\n上午处理高风险阻塞项，下午集中关闭资料与验收类任务。若现场条件变化，请及时在 Primelayer 更新状态。'),
      {
        tag: 'action',
        actions: [
          buttonAction('查看待办', 'open_daily_todos', 'primary'),
          buttonAction('标记已读', 'ack_daily_todos'),
          buttonAction('稍后提醒', 'snooze_daily_todos')
        ]
      },
      noteBlock('测试模板：按钮仅验证卡片渲染，暂未接入飞书卡片回调')
    ]
  }
}

function createConstructionDailyCard() {
  return {
    config: {
      wide_screen_mode: true
    },
    header: {
      title: {
        tag: 'plain_text',
        content: '施工日报｜Roche 项目'
      },
      template: 'orange'
    },
    elements: [
      textBlock(`**日报日期：${currentDateLabel()}**\n由 Primelayer Agent 汇总现场进度、安全质量、资源投入与明日计划。`),
      fieldBlock([
        '**今日进度**\n完成 86%',
        '**现场人数**\n42 人',
        '**安全状态**\n0 起事故',
        '**质量问题**\n3 项待关闭'
      ]),
      {
        tag: 'hr'
      },
      textBlock('**今日完成**\n- 2F 弱电桥架安装完成 120m\n- 3F 隔墙龙骨复核完成，偏差均在允许范围内\n- B1 材料堆场完成重新分区，通道已恢复'),
      textBlock('**异常与风险**\n- [中] 空调末端设备到场延迟，预计影响 3F 天花封板\n- [低] 2F 东侧交叉作业密度较高，需加强旁站协调\n- [正常] 临电巡检正常，消防通道保持畅通'),
      textBlock('**明日计划**\n1. 继续推进 3F 机电综合点位复核\n2. 完成 2F 东侧隐蔽验收资料归档\n3. 跟进空调末端设备物流与到场验收'),
      {
        tag: 'action',
        actions: [
          buttonAction('查看完整日报', 'open_construction_daily', 'primary'),
          buttonAction('同步到项目群', 'share_construction_daily'),
          buttonAction('补充现场照片', 'attach_site_photos')
        ]
      },
      noteBlock('施工日报模板适合发送到项目群；批量发给个人时可用于确认日报样式')
    ]
  }
}

function createRiskAlertCard() {
  return {
    config: {
      wide_screen_mode: true
    },
    header: {
      title: {
        tag: 'plain_text',
        content: '项目风险提醒｜需要关注'
      },
      template: 'red'
    },
    elements: [
      textBlock('**风险等级：高**\nPrimelayer Agent 检测到 2 个可能影响节点交付的风险，请项目负责人优先确认。'),
      fieldBlock([
        '**项目**\nRoche 项目',
        '**风险来源**\n施工进度 / 材料计划',
        '**影响节点**\n3F 天花封板',
        '**建议处理时限**\n今日 18:00 前'
      ]),
      {
        tag: 'hr'
      },
      textBlock('**风险详情**\n1. 空调末端设备未按计划到场，可能造成后续吊顶封板延期。\n2. 机电点位复核仍有 5 处未确认，可能影响隐蔽验收。'),
      textBlock('**Agent 建议**\n- 指派材料负责人确认物流 ETA\n- 将未确认点位拆分到责任班组\n- 今日收工前同步一次风险关闭状态'),
      {
        tag: 'action',
        actions: [
          buttonAction('查看风险详情', 'open_risk_detail', 'primary'),
          buttonAction('创建跟进项', 'create_follow_up'),
          buttonAction('我已知晓', 'ack_risk_alert')
        ]
      },
      noteBlock('风险模板用于验证高亮标题、字段摘要和多按钮操作布局')
    ]
  }
}

function createWeeklySummaryCard() {
  return {
    config: {
      wide_screen_mode: true
    },
    header: {
      title: {
        tag: 'plain_text',
        content: '项目周报摘要'
      },
      template: 'purple'
    },
    elements: [
      textBlock('**本周项目状态：总体可控**\n本摘要由 Primelayer Agent 根据任务、风险和现场日报数据生成，用于周会前快速同步。'),
      fieldBlock([
        '**完成事项**\n24 项',
        '**延期事项**\n3 项',
        '**新增风险**\n2 项',
        '**下周重点**\n机电验收 / 材料到场'
      ]),
      {
        tag: 'hr'
      },
      textBlock('**本周亮点**\n- 2F 弱电桥架安装推进快于计划\n- 安全文明施工检查连续 5 天无重大问题\n- 隐蔽验收资料归档效率提升'),
      textBlock('**阻塞项**\n- 3F 空调末端设备到场时间待确认\n- 部分变更签证资料仍需补齐附件'),
      textBlock('**下周建议**\n1. 周一上午锁定材料到场计划\n2. 周三前完成 3F 机电综合点位确认\n3. 周五前关闭本周遗留质量问题'),
      {
        tag: 'action',
        actions: [
          buttonAction('查看完整周报', 'open_weekly_summary', 'primary'),
          buttonAction('导出会议纪要', 'export_meeting_notes')
        ]
      },
      noteBlock('周报模板适合后续扩展为 Primelayer AI 自动生成内容')
    ]
  }
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
