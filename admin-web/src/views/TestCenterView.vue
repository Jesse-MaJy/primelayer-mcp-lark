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
            <a-form-item label="项目 Token">
              <a-select
                v-model:value="mcp.tokenId"
                :options="tokenOptions"
                show-search
                option-filter-prop="label"
                placeholder="选择后台已保存的 token"
              />
            </a-form-item>
            <a-form-item label="工具名">
              <a-input v-model:value="mcp.toolName" />
            </a-form-item>
            <a-form-item label="参数 JSON">
              <a-textarea v-model:value="mcp.argumentsJson" :rows="10" />
            </a-form-item>
            <a-button type="primary" :loading="loading.mcp" @click="runMcpCall">调用 MCP</a-button>
          </a-form>
          <ResultViewer :data="mcp.result" />
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
          <ResultViewer :data="agent.result" />
        </div>
      </a-tab-pane>

      <a-tab-pane key="feishu" tab="飞书事件测试">
        <div class="split-grid">
          <a-form layout="vertical">
            <a-form-item label="飞书事件 JSON">
              <a-textarea v-model:value="feishu.eventJson" :rows="18" />
            </a-form-item>
            <a-button type="primary" :loading="loading.feishu" @click="runFeishuMock">模拟事件</a-button>
          </a-form>
          <ResultViewer :data="feishu.result" />
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

const activeTab = ref('health')
const health = ref<Record<string, unknown> | null>(null)
const tokenOptions = ref<{ label: string; value: number }[]>([])

const loading = reactive({
  health: false,
  plan: false,
  summarize: false,
  mcp: false,
  agent: false,
  feishu: false
})

const deepseek = reactive({
  question: '帮我看一下 Roche 项目的风险',
  chatType: 'p2p',
  toolResultsJson: JSON.stringify([
    { projectId: 'roche', status: 'SUCCEEDED', result: { risks: ['示例风险'] } }
  ], null, 2),
  result: null as unknown
})

const mcp = reactive({
  tokenId: undefined as number | undefined,
  toolName: 'primelayer.query_tasks',
  argumentsJson: JSON.stringify({ question: '帮我看一下 Roche 项目的风险' }, null, 2),
  result: null as unknown
})

const agent = reactive({
  feishuOpenId: '',
  feishuChatId: 'debug-chat',
  chatType: 'p2p',
  message: '帮我看一下 Roche 项目的风险',
  result: null as unknown
})

const feishu = reactive({
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
    { label: '飞书 App ID', ok: value.feishuAppIdConfigured === true, value: value.feishuAppIdConfigured ? '已配置' : '未配置' },
    { label: '飞书 Secret', ok: value.feishuAppSecretConfigured === true, value: value.feishuAppSecretConfigured ? '已配置' : '未配置' },
    { label: '飞书 Verification Token', ok: value.feishuVerificationTokenConfigured === true, value: value.feishuVerificationTokenConfigured ? '已配置' : '未配置' }
  ]
})

async function refreshAll() {
  await Promise.all([loadHealth(), loadTokens()])
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

async function runMcpCall() {
  if (!mcp.tokenId) {
    message.warning('请先选择项目 Token')
    return
  }
  loading.mcp = true
  try {
    mcp.result = await adminApi.debugMcpCall({
      tokenId: mcp.tokenId,
      toolName: mcp.toolName,
      arguments: parseJson(mcp.argumentsJson)
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

.section-alert {
  margin-bottom: 16px;
}

.result-block {
  margin-top: 16px;
}

@media (max-width: 960px) {
  .split-grid {
    grid-template-columns: 1fr;
  }
}
</style>
