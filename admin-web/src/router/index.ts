import { createRouter, createWebHistory } from 'vue-router'
import LoginView from '../views/LoginView.vue'
import AdminLayout from '../views/AdminLayout.vue'
import PeopleConfigView from '../views/PeopleConfigView.vue'
import ChatBindingsView from '../views/ChatBindingsView.vue'
import AuditLogsView from '../views/AuditLogsView.vue'
import AgentTasksView from '../views/AgentTasksView.vue'
import ChainTraceView from '../views/ChainTraceView.vue'
import FeishuMessagesView from '../views/FeishuMessagesView.vue'
import TestCenterView from '../views/TestCenterView.vue'
import SystemSettingsView from '../views/SystemSettingsView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView },
    {
      path: '/',
      component: AdminLayout,
      redirect: '/people-configs',
      children: [
        { path: 'people-configs', component: PeopleConfigView },
        { path: 'user-bindings', redirect: '/people-configs' },
        { path: 'project-tokens', redirect: '/people-configs' },
        { path: 'chat-bindings', component: ChatBindingsView },
        { path: 'system-settings', component: SystemSettingsView },
        { path: 'audit-logs', component: AuditLogsView },
        { path: 'agent-tasks', component: AgentTasksView },
        { path: 'chain-trace/:requestId', component: ChainTraceView },
        { path: 'feishu-messages', component: FeishuMessagesView },
        { path: 'test-center', component: TestCenterView }
      ]
    }
  ]
})

router.beforeEach((to) => {
  if (to.path !== '/login' && !localStorage.getItem('admin_token')) {
    return '/login'
  }
})

export default router
