<template>
  <section class="page people-config">
    <div class="page-header">
      <div>
        <h1 class="page-title">人员配置</h1>
        <div class="page-subtitle">Token 仅绑定飞书 open_id。单个可用 Token 自动作为默认；多个 Token 自动识别项目，未识别时按名称查询前 20 个项目。</div>
      </div>
      <div class="toolbar">
        <a-button @click="refresh">刷新</a-button>
        <a-button @click="openUserModal()">新增 / 替换人员</a-button>
        <a-button type="primary" @click="openTokenWizard()">配置 MCP Token</a-button>
      </div>
    </div>

    <div class="status-grid">
      <div class="status-tile wide">
        <div class="status-label">MCP 地址</div>
        <div class="status-value text">{{ mcpEndpoint }}</div>
      </div>
      <div class="status-tile">
        <div class="status-label">认证 Header</div>
        <div class="status-value">{{ mcpAuthHeaderName }}</div>
      </div>
      <div class="status-tile">
        <div class="status-label">已配置 Token</div>
        <div class="status-value">{{ projectTokens.length }}</div>
      </div>
      <div class="status-tile">
        <div class="status-label">ACTIVE / 可查询</div>
        <div class="status-value">{{ activeTokenCount }} / {{ eligibleTokenCount }}</div>
      </div>
      <div class="status-tile">
        <div class="status-label">未配置人员</div>
        <div class="status-value warn">{{ unconfiguredPeopleCount }}</div>
      </div>
      <div class="status-tile">
        <div class="status-label">异常 / 待补验</div>
        <div class="status-value danger">{{ unavailableVerifyCount }}</div>
      </div>
    </div>

    <div class="section-block">
      <div class="section-title">人员 → 项目 Token</div>
      <a-table
        :columns="combinedColumns"
        :data-source="combinedRows"
        :loading="loading"
        row-key="key"
        :pagination="{ pageSize: 8 }"
        :scroll="{ x: 1280 }"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'tokenStatus'">
            <a-tag :color="tokenStatusColor(record.tokenStatus)">{{ record.tokenStatus || '未配置' }}</a-tag>
          </template>
          <template v-else-if="column.key === 'verifyStatus'">
            <a-tag :color="verifyStatusColor(record.verifyStatus)">{{ verifyStatusLabel(record.verifyStatus) }}</a-tag>
          </template>
          <template v-else-if="column.key === 'action'">
            <a-button type="link" @click="openTokenWizard(record)">{{ record.id ? '编辑 / 替换' : '配置 Token' }}</a-button>
          </template>
        </template>
      </a-table>
    </div>

    <a-tabs class="section-block">
      <a-tab-pane key="people" tab="人员备注">
        <a-table
          :columns="userColumns"
          :data-source="userBindings"
          :loading="loading"
          row-key="id"
          :scroll="{ x: 960 }"
        >
          <template #bodyCell="{ column, record }">
            <a-button v-if="column.key === 'action'" type="link" @click="openUserModal(record)">编辑</a-button>
          </template>
        </a-table>
      </a-tab-pane>

      <a-tab-pane key="tokens" tab="项目 MCP Token">
        <a-table
          :columns="tokenColumns"
          :data-source="tokenRows"
          :loading="loading"
          row-key="id"
          :scroll="{ x: 1280 }"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'verify_status'">
              <a-tag :color="verifyStatusColor(record.verify_status)">{{ verifyStatusLabel(record.verify_status) }}</a-tag>
            </template>
            <template v-else-if="column.key === 'action'">
              <a-button type="link" @click="openTokenWizard(record)">编辑 / 替换</a-button>
            </template>
          </template>
        </a-table>
      </a-tab-pane>
    </a-tabs>

    <a-modal v-model:open="userModalOpen" title="人员备注" @ok="saveUser">
      <a-form layout="vertical">
        <a-form-item label="人名">
          <a-input v-model:value="userForm.personName" placeholder="例如：张三" />
        </a-form-item>
        <a-form-item label="飞书 open_id" required>
          <a-input v-model:value="userForm.feishuOpenId" />
        </a-form-item>
        <a-form-item label="状态">
          <a-select v-model:value="userForm.status" :options="statusOptions" />
        </a-form-item>
      </a-form>
    </a-modal>

    <a-drawer
      v-model:open="tokenDrawerOpen"
      title="MCP Token 配置向导"
      width="640"
      :destroy-on-close="false"
    >
      <a-steps size="small" :current="tokenStepIndex" :items="tokenStepItems" />

      <div class="wizard-body">
        <div v-if="tokenStepIndex === 0">
          <a-alert class="section-alert" type="info" show-icon message="先选择飞书人员，Token 会直接保存到该 open_id 下；不需要 Primelayer 账号。" />
          <a-form layout="vertical">
            <a-form-item label="选择人员" required>
              <a-select
                v-model:value="tokenForm.ownerId"
                :options="peopleOptions"
                show-search
                option-filter-prop="label"
                placeholder="选择飞书人员 open_id"
              />
            </a-form-item>
          </a-form>
          <a-descriptions v-if="selectedUser" bordered :column="1" size="small">
            <a-descriptions-item label="人名">{{ displayName(selectedUser) }}</a-descriptions-item>
            <a-descriptions-item label="飞书 open_id">{{ text(selectedUser, 'feishu_open_id') }}</a-descriptions-item>
          </a-descriptions>
        </div>

        <div v-else-if="tokenStepIndex === 1">
          <a-alert class="section-alert" type="warning" show-icon :message="hasExistingToken ? '请粘贴新的 MCP Token。验证通过并保存后，会替换当前项目下的旧 Token。' : '请粘贴新的 MCP Token。历史 Token 明文不会展示；保存后会绑定到所选人员和项目。'" />
          <a-form layout="vertical">
            <a-form-item label="MCP Token" required>
              <a-input-password v-model:value="tokenForm.mcpToken" placeholder="粘贴 Primelayer MCP Token" />
            </a-form-item>
            <a-form-item label="状态">
              <a-select v-model:value="tokenForm.tokenStatus" :options="statusOptions" />
            </a-form-item>
          </a-form>
        </div>

        <div v-else-if="tokenStepIndex === 2">
          <a-alert
            v-if="!verifyResult && !keepingExistingToken"
            class="section-alert"
            type="info"
            show-icon
            message="点击验证后，系统会调用 MCP tools/list，并尝试自动识别项目。验证不会保存 Token。"
          />
          <a-alert
            v-if="keepingExistingToken"
            class="section-alert"
            type="success"
            show-icon
            message="当前使用已保存并验证过的 Token。可以直接调整项目备注或状态；如需重置 Token，请点击替换 Token。"
          />
          <a-button v-if="hasExistingToken && tokenStepIndex === 2" class="section-alert" @click="startReplaceToken">替换 Token</a-button>
          <div v-if="verifyResult" class="verify-panel">
            <a-descriptions bordered :column="2" size="small">
              <a-descriptions-item label="验证状态">
                <a-tag color="green">通过</a-tag>
              </a-descriptions-item>
              <a-descriptions-item label="工具数量">{{ verifyResult.toolCount ?? 0 }}</a-descriptions-item>
              <a-descriptions-item label="Token Hash 后缀">{{ verifyResult.tokenHashSuffix || '-' }}</a-descriptions-item>
              <a-descriptions-item label="项目候选">{{ projectCandidates.length }}</a-descriptions-item>
              <a-descriptions-item label="账号上下文">{{ verifyResult.mcpUserId || '由 Token 管理' }}</a-descriptions-item>
            </a-descriptions>
            <a-alert
              v-for="warning in verifyWarnings"
              :key="warning"
              class="section-alert"
              type="warning"
              show-icon
              :message="warning"
            />
            <div v-if="projectCandidates.length" class="candidate-list">
              <div class="section-title compact">选择项目</div>
              <a-radio-group v-model:value="selectedProjectKey">
                <a-radio
                  v-for="candidate in projectCandidates"
                  :key="projectKey(candidate)"
                  :value="projectKey(candidate)"
                  class="candidate-option"
                >
                  <span class="candidate-name">{{ candidate.projectName }}</span>
                  <span class="candidate-meta">{{ candidate.projectId }}</span>
                </a-radio>
              </a-radio-group>
            </div>
          </div>

          <a-collapse v-model:active-key="manualActiveKey" class="section-block">
            <a-collapse-panel key="manual" header="高级手动填写项目">
              <a-alert class="section-alert" type="warning" show-icon message="只有在 Token 可用但无法自动解析项目时使用。保存前必须勾选确认。" />
              <a-form layout="vertical">
                <a-form-item label="项目备注名">
                  <a-input v-model:value="tokenForm.projectName" placeholder="例如：Roche / 罗诊 / 上海一期" />
                </a-form-item>
                <a-form-item label="项目标识（可选）">
                  <a-input v-model:value="tokenForm.projectId" placeholder="不填时默认使用项目备注名" />
                </a-form-item>
                <a-checkbox v-model:checked="tokenForm.manualProjectConfirmed">确认使用手动填写的项目信息</a-checkbox>
              </a-form>
            </a-collapse-panel>
          </a-collapse>
        </div>

        <div v-else>
          <a-descriptions bordered :column="1" size="small">
            <a-descriptions-item label="绑定对象">{{ selectedUserLabel || '-' }}</a-descriptions-item>
            <a-descriptions-item label="项目备注名">{{ finalProjectName || '-' }}</a-descriptions-item>
            <a-descriptions-item label="项目标识">{{ finalProjectId || '-' }}</a-descriptions-item>
            <a-descriptions-item label="保存方式">{{ keepingExistingToken ? '保留已有 Token' : tokenForm.manualProjectConfirmed ? '手动确认项目' : 'Token 自动验证' }}</a-descriptions-item>
          </a-descriptions>
        </div>
      </div>

      <template #footer>
        <div class="drawer-footer">
          <a-button @click="tokenDrawerOpen = false">取消</a-button>
          <a-button v-if="tokenStepIndex > 0" @click="tokenStepIndex -= 1">上一步</a-button>
          <a-button v-if="tokenStepIndex < 2" type="primary" @click="nextTokenStep">下一步</a-button>
          <a-button v-else-if="tokenStepIndex === 2 && !verifyResult && !keepingExistingToken" type="primary" :loading="verifying" @click="verifyToken">验证 Token</a-button>
          <a-button v-else-if="tokenStepIndex === 2" type="primary" @click="confirmProjectStep">下一步</a-button>
          <a-button v-if="canShowSave" type="primary" :loading="savingToken" @click="saveToken">保存配置</a-button>
        </div>
      </template>
    </a-drawer>
  </section>
</template>

<script setup lang="ts">
import { computed, onMounted, reactive, ref, watch } from 'vue'
import { message } from 'ant-design-vue'
import { adminApi } from '../api/admin'
import { formatChinaDateTime, timeColumn } from '../utils/time'

type DataRow = Record<string, unknown>

const loading = ref(false)
const verifying = ref(false)
const savingToken = ref(false)
const userModalOpen = ref(false)
const tokenDrawerOpen = ref(false)
const tokenStepIndex = ref(0)
const userBindings = ref<DataRow[]>([])
const projectTokens = ref<DataRow[]>([])
const health = ref<DataRow | null>(null)
const verifyResult = ref<DataRow | null>(null)
const selectedProjectKey = ref('')
const manualActiveKey = ref<string[]>([])
const editingTokenId = ref<number | null>(null)
const replaceTokenMode = ref(false)

const statusOptions = [
  { label: 'ACTIVE', value: 'ACTIVE' },
  { label: 'DISABLED', value: 'DISABLED' }
]

const tokenStepItems = [
  { title: '选择人员' },
  { title: '输入 Token' },
  { title: '验证/备注项目' },
  { title: '确认保存' }
]

const userForm = reactive({
  personName: '',
  feishuOpenId: '',
  status: 'ACTIVE'
})

const tokenForm = reactive({
  ownerId: '',
  projectId: '',
  projectName: '',
  mcpToken: '',
  tokenStatus: 'ACTIVE',
  manualProjectConfirmed: false
})

const combinedColumns = [
  { title: '人名', dataIndex: 'personName', fixed: 'left', width: 140 },
  { title: '飞书 open_id', dataIndex: 'feishuOpenId', width: 260 },
  { title: 'Token 绑定 open_id', dataIndex: 'ownerId', width: 260 },
  { title: '项目备注名', dataIndex: 'projectName', width: 180 },
  { title: '项目标识', dataIndex: 'projectId', width: 180 },
  { title: 'Token Hash 后缀', dataIndex: 'tokenHashSuffix', width: 150 },
  { title: 'Token 状态', key: 'tokenStatus', dataIndex: 'tokenStatus', width: 120 },
  { title: '验证状态', key: 'verifyStatus', dataIndex: 'verifyStatus', width: 120 },
  { title: '最近验证', dataIndex: 'lastVerifiedAt', width: 180 },
  { title: '操作', key: 'action', fixed: 'right', width: 120 }
]

const userColumns = [
  { title: '人名', dataIndex: 'person_name' },
  { title: '飞书 open_id', dataIndex: 'feishu_open_id' },
  { title: '状态', dataIndex: 'status' },
  timeColumn('更新时间', 'updated_at'),
  { title: '操作', key: 'action', fixed: 'right', width: 96 }
]

const tokenColumns = [
  { title: '人名', dataIndex: 'personName' },
  { title: '飞书 open_id', dataIndex: 'feishu_open_id' },
  { title: 'MCP 用户 ID（可选）', dataIndex: 'mcp_user_id' },
  { title: '项目备注名', dataIndex: 'project_name' },
  { title: '项目标识', dataIndex: 'project_id' },
  { title: 'Token Hash 后缀', dataIndex: 'token_hash_suffix' },
  { title: '状态', dataIndex: 'token_status' },
  { title: '验证状态', key: 'verify_status', dataIndex: 'verify_status' },
  timeColumn('最近验证', 'last_verified_at'),
  { title: '导入人', dataIndex: 'imported_by' },
  timeColumn('最近使用', 'last_used_at'),
  { title: '操作', key: 'action', fixed: 'right', width: 112 }
]

const mcpEndpoint = computed(() => String(health.value?.mcpEndpoint || '-'))
const mcpAuthHeaderName = computed(() => String(health.value?.mcpAuthHeaderName || '-'))

const activeTokenCount = computed(() => projectTokens.value.filter((row) => text(row, 'token_status') === 'ACTIVE').length)
const eligibleTokenCount = computed(() => projectTokens.value.filter((row) =>
  text(row, 'token_status') === 'ACTIVE'
  && ['VERIFIED', 'MANUAL'].includes(text(row, 'verify_status'))
).length)
const unavailableVerifyCount = computed(() => projectTokens.value.filter((row) =>
  !['VERIFIED', 'MANUAL'].includes(text(row, 'verify_status'))
).length)
const unconfiguredPeopleCount = computed(() => {
  const configured = new Set(projectTokens.value.map(tokenOwnerId).filter(Boolean))
  return userBindings.value.filter((row) => !configured.has(text(row, 'feishu_open_id'))).length
})

const peopleOptions = computed(() => userBindings.value.map((row) => {
  const openId = text(row, 'feishu_open_id')
  return {
    value: openId,
    label: `${displayName(row)} / ${openId}`
  }
}).filter((option) => option.value))

const userByOpenId = computed(() => {
  const map = new Map<string, DataRow>()
  for (const row of userBindings.value) {
    const id = text(row, 'feishu_open_id')
    if (id && !map.has(id)) {
      map.set(id, row)
    }
  }
  return map
})

const selectedUser = computed(() => userByOpenId.value.get(tokenForm.ownerId))
const selectedUserLabel = computed(() => selectedUser.value ? `${displayName(selectedUser.value)} / ${tokenForm.ownerId}` : tokenForm.ownerId)

const tokenRows = computed(() => projectTokens.value.map((token) => ({
  ...token,
  personName: displayName(userByOpenId.value.get(tokenOwnerId(token)))
})))

const combinedRows = computed(() => {
  const tokensByUser = new Map<string, DataRow[]>()
  for (const token of projectTokens.value) {
    const userId = tokenOwnerId(token)
    tokensByUser.set(userId, [...(tokensByUser.get(userId) || []), token])
  }
  return userBindings.value.flatMap((user) => {
    const userId = text(user, 'feishu_open_id')
    const tokens = tokensByUser.get(userId) || []
    if (tokens.length === 0) {
      return [combinedRow(user, null)]
    }
    return tokens.map((token) => combinedRow(user, token))
  })
})

const projectCandidates = computed<DataRow[]>(() => {
  const value = verifyResult.value?.projectCandidates
  return Array.isArray(value) ? value as DataRow[] : []
})

const verifyWarnings = computed<string[]>(() => {
  const value = verifyResult.value?.warnings
  return Array.isArray(value) ? value.map(String) : []
})

const selectedProject = computed(() => projectCandidates.value.find((candidate) => projectKey(candidate) === selectedProjectKey.value))
const finalProjectId = computed(() => text(selectedProject.value, 'projectId') || tokenForm.projectId || tokenForm.projectName)
const finalProjectName = computed(() => text(selectedProject.value, 'projectName') || tokenForm.projectName)
const canShowSave = computed(() => tokenStepIndex.value === 3)
const hasExistingToken = computed(() => editingTokenId.value != null)
const keepingExistingToken = computed(() => hasExistingToken.value && !replaceTokenMode.value)

watch(selectedProject, (project) => {
  if (project) {
    tokenForm.projectId = text(project, 'projectId')
    tokenForm.projectName = text(project, 'projectName')
    tokenForm.manualProjectConfirmed = false
  }
})

async function refresh() {
  loading.value = true
  try {
    const [bindings, tokens, healthResult] = await Promise.all([
      adminApi.listUserBindings(),
      adminApi.listProjectTokens(),
      adminApi.debugHealth()
    ])
    userBindings.value = bindings
    projectTokens.value = tokens
    health.value = healthResult
  } catch (error) {
    showError(error, '加载失败')
  } finally {
    loading.value = false
  }
}

function openUserModal(row?: DataRow) {
  userForm.personName = text(row, 'person_name')
  userForm.feishuOpenId = text(row, 'feishu_open_id')
  userForm.status = text(row, 'status') || 'ACTIVE'
  userModalOpen.value = true
}

async function saveUser() {
  try {
    await adminApi.saveUserBinding({ ...userForm })
    userModalOpen.value = false
    message.success('人员备注已保存')
    await refresh()
  } catch (error) {
    showError(error, '保存失败')
  }
}

function openTokenWizard(row?: DataRow) {
  const tokenId = text(row, 'id')
  editingTokenId.value = tokenId ? Number(tokenId) : null
  replaceTokenMode.value = !editingTokenId.value
  tokenForm.ownerId = text(row, 'feishu_open_id') || text(row, 'feishuOpenId')
  tokenForm.projectId = text(row, 'project_id') || text(row, 'projectId') || ''
  tokenForm.projectName = text(row, 'project_name') || text(row, 'projectName') || ''
  tokenForm.mcpToken = ''
  tokenForm.tokenStatus = text(row, 'token_status') || text(row, 'tokenStatus') || 'ACTIVE'
  tokenForm.manualProjectConfirmed = Boolean(editingTokenId.value)
  verifyResult.value = null
  selectedProjectKey.value = ''
  manualActiveKey.value = editingTokenId.value ? ['manual'] : []
  tokenStepIndex.value = tokenForm.ownerId ? (editingTokenId.value ? 2 : 1) : 0
  tokenDrawerOpen.value = true
}

function nextTokenStep() {
  if (tokenStepIndex.value === 0 && !tokenForm.ownerId) {
    message.warning('请先选择人员')
    return
  }
  if (tokenStepIndex.value === 1 && !tokenForm.mcpToken.trim()) {
    message.warning('请先输入 MCP Token')
    return
  }
  tokenStepIndex.value += 1
}

function startReplaceToken() {
  replaceTokenMode.value = true
  verifyResult.value = null
  selectedProjectKey.value = ''
  tokenForm.mcpToken = ''
  tokenForm.manualProjectConfirmed = false
  tokenStepIndex.value = 1
}

async function verifyToken() {
  if (!tokenForm.ownerId) {
    message.warning('请先选择人员')
    tokenStepIndex.value = 0
    return
  }
  if (!tokenForm.mcpToken.trim()) {
    message.warning('请先输入 MCP Token')
    tokenStepIndex.value = 1
    return
  }
  verifying.value = true
  try {
    const result = await adminApi.verifyProjectToken({
      feishuOpenId: tokenForm.ownerId,
      mcpToken: tokenForm.mcpToken
    })
    verifyResult.value = result
    const selected = result.selectedProject as DataRow | undefined
    if (selected) {
      selectedProjectKey.value = projectKey(selected)
      tokenForm.projectId = text(selected, 'projectId')
      tokenForm.projectName = text(selected, 'projectName')
    } else {
      const first = projectCandidates.value[0]
      selectedProjectKey.value = first ? projectKey(first) : ''
      tokenForm.projectId = first ? text(first, 'projectId') : ''
      tokenForm.projectName = first ? text(first, 'projectName') : ''
      manualActiveKey.value = ['manual']
    }
    message.success('MCP Token 验证通过')
  } catch (error) {
    verifyResult.value = null
    showError(error, 'Token 验证失败')
  } finally {
    verifying.value = false
  }
}

function confirmProjectStep() {
  if (!verifyResult.value && !keepingExistingToken.value) {
    message.warning('请先验证 MCP Token')
    return
  }
  if (!finalProjectId.value || !finalProjectName.value) {
    message.warning('请确认项目备注名')
    manualActiveKey.value = ['manual']
    return
  }
  if (!selectedProject.value && !tokenForm.manualProjectConfirmed && !keepingExistingToken.value) {
    message.warning('手动项目需要先勾选确认')
    manualActiveKey.value = ['manual']
    return
  }
  tokenStepIndex.value = 3
}

async function saveToken() {
  if (!verifyResult.value && !keepingExistingToken.value) {
    message.warning('请先验证 MCP Token')
    tokenStepIndex.value = 2
    return
  }
  if (!finalProjectId.value || !finalProjectName.value) {
    message.warning('请确认项目备注名')
    manualActiveKey.value = ['manual']
    return
  }
  if (!selectedProject.value && !tokenForm.manualProjectConfirmed && !keepingExistingToken.value) {
    message.warning('手动项目需要先勾选确认')
    manualActiveKey.value = ['manual']
    return
  }
  savingToken.value = true
  try {
    await adminApi.saveProjectToken({
      id: editingTokenId.value,
      feishuOpenId: tokenForm.ownerId,
      projectId: finalProjectId.value,
      projectName: finalProjectName.value,
      projectRemark: finalProjectName.value,
      mcpToken: replaceTokenMode.value ? tokenForm.mcpToken : '',
      tokenStatus: tokenForm.tokenStatus,
      replaceToken: replaceTokenMode.value,
      manualProjectConfirmed: keepingExistingToken.value || !selectedProject.value || tokenForm.manualProjectConfirmed
    })
    tokenDrawerOpen.value = false
    message.success('MCP Token 已保存')
    await refresh()
  } catch (error) {
    showError(error, '保存失败')
  } finally {
    savingToken.value = false
  }
}

function combinedRow(user: DataRow, token: DataRow | null) {
  return {
    key: `${text(user, 'id')}-${token ? text(token, 'id') : 'no-token'}`,
    id: token ? text(token, 'id') : '',
    feishu_open_id: token ? tokenOwnerId(token) : text(user, 'feishu_open_id'),
    project_id: token ? text(token, 'project_id') : '',
    project_name: token ? text(token, 'project_name') : '',
    personName: displayName(user),
    feishuOpenId: text(user, 'feishu_open_id'),
    ownerId: token ? tokenOwnerId(token) : text(user, 'feishu_open_id'),
    projectName: token ? text(token, 'project_name') : '-',
    projectId: token ? text(token, 'project_id') : '-',
    tokenHashSuffix: token ? text(token, 'token_hash_suffix') : '-',
    tokenStatus: token ? text(token, 'token_status') : '未配置',
    verifyStatus: token ? text(token, 'verify_status') : '',
    lastVerifiedAt: token ? formatChinaDateTime(text(token, 'last_verified_at')) : '-'
  }
}

function projectKey(project: DataRow) {
  return `${text(project, 'projectId')}::${text(project, 'projectName')}`
}

function tokenStatusColor(status: unknown) {
  const value = String(status || '')
  if (value === 'ACTIVE') return 'green'
  if (value === '未配置') return 'default'
  return 'red'
}

function verifyStatusColor(status: unknown) {
  const value = String(status || '')
  if (value === 'VERIFIED') return 'green'
  if (value === 'MANUAL') return 'orange'
  if (value === 'FAILED') return 'red'
  return 'default'
}

function verifyStatusLabel(status: unknown) {
  const value = String(status || '')
  if (value === 'VERIFIED') return '已验证'
  if (value === 'MANUAL') return '手动确认'
  if (value === 'FAILED') return '验证失败'
  return '未验证'
}

function displayName(row?: DataRow) {
  if (!row) {
    return '-'
  }
  return text(row, 'person_name') || text(row, 'feishu_open_id') || '-'
}

function tokenOwnerId(row: DataRow) {
  return text(row, 'feishu_open_id')
}

function text(row: DataRow | undefined | null, key: string) {
  const value = row?.[key]
  return value == null ? '' : String(value)
}

function showError(error: unknown, fallback: string) {
  message.error(error instanceof Error ? error.message : fallback)
}

onMounted(refresh)
</script>

<style scoped>
.page-subtitle {
  margin-top: 6px;
  color: #667085;
  font-size: 13px;
}

.status-grid {
  display: grid;
  grid-template-columns: minmax(320px, 2fr) repeat(5, minmax(120px, 1fr));
  gap: 12px;
  margin-bottom: 18px;
}

.status-tile {
  min-height: 82px;
  padding: 14px 16px;
  background: #fff;
  border: 1px solid #e6eaf2;
  border-radius: 8px;
}

.status-label {
  color: #667085;
  font-size: 12px;
}

.status-value {
  margin-top: 10px;
  color: #172033;
  font-size: 24px;
  font-weight: 700;
  word-break: break-all;
}

.status-value.text {
  font-size: 13px;
  line-height: 1.5;
  font-weight: 600;
}

.status-value.warn {
  color: #d97008;
}

.status-value.danger {
  color: #d92d20;
}

.section-alert {
  margin-bottom: 16px;
}

.section-block {
  margin-top: 16px;
}

.section-title {
  margin-bottom: 12px;
  font-size: 15px;
  font-weight: 650;
}

.section-title.compact {
  margin-top: 16px;
  margin-bottom: 8px;
}

.wizard-body {
  margin-top: 24px;
}

.verify-panel {
  margin-bottom: 16px;
}

.candidate-list {
  margin-top: 16px;
}

.candidate-option {
  display: block;
  min-height: 42px;
  padding: 8px 0;
}

.candidate-name {
  display: block;
  color: #172033;
  font-weight: 650;
}

.candidate-meta {
  display: block;
  margin-top: 2px;
  color: #667085;
  font-size: 12px;
}

.drawer-footer {
  display: flex;
  justify-content: flex-end;
  gap: 8px;
}

@media (max-width: 1100px) {
  .status-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}
</style>
