import { createRouter, createWebHistory } from 'vue-router'
import LoginView from '../views/LoginView.vue'
import AdminLayout from '../views/AdminLayout.vue'
import UserBindingsView from '../views/UserBindingsView.vue'
import ProjectTokensView from '../views/ProjectTokensView.vue'
import ChatBindingsView from '../views/ChatBindingsView.vue'
import AuditLogsView from '../views/AuditLogsView.vue'
import AgentTasksView from '../views/AgentTasksView.vue'
import TestCenterView from '../views/TestCenterView.vue'

const router = createRouter({
  history: createWebHistory(),
  routes: [
    { path: '/login', component: LoginView },
    {
      path: '/',
      component: AdminLayout,
      redirect: '/user-bindings',
      children: [
        { path: 'user-bindings', component: UserBindingsView },
        { path: 'project-tokens', component: ProjectTokensView },
        { path: 'chat-bindings', component: ChatBindingsView },
        { path: 'audit-logs', component: AuditLogsView },
        { path: 'agent-tasks', component: AgentTasksView },
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
