<template>
  <a-layout class="layout">
    <a-layout-sider width="232" theme="light">
      <div class="brand">Lark Connect</div>
      <a-menu mode="inline" :selected-keys="[route.path]">
        <a-menu-item key="/people-configs" @click="go('/people-configs')">人员配置</a-menu-item>
        <a-menu-item key="/chat-bindings" @click="go('/chat-bindings')">群项目绑定</a-menu-item>
        <a-menu-item key="/system-settings" @click="go('/system-settings')">系统配置</a-menu-item>
        <a-menu-item key="/test-center" @click="go('/test-center')">测试中心</a-menu-item>
        <a-menu-item key="/feishu-messages" @click="go('/feishu-messages')">消息记录</a-menu-item>
        <a-menu-item key="/audit-logs" @click="go('/audit-logs')">审计日志</a-menu-item>
        <a-menu-item key="/agent-tasks" @click="go('/agent-tasks')">任务状态</a-menu-item>
      </a-menu>
    </a-layout-sider>
    <a-layout>
      <a-layout-header class="topbar">
        <a-button @click="logout">退出</a-button>
      </a-layout-header>
      <a-layout-content>
        <router-view />
      </a-layout-content>
    </a-layout>
  </a-layout>
</template>

<script setup lang="ts">
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '../stores/auth'

const route = useRoute()
const router = useRouter()
const auth = useAuthStore()

function go(path: string) {
  router.push(path)
}

function logout() {
  auth.logout()
  router.push('/login')
}
</script>

<style scoped>
.layout {
  min-height: 100vh;
}

.brand {
  height: 56px;
  display: flex;
  align-items: center;
  padding: 0 20px;
  font-size: 18px;
  font-weight: 700;
}

.topbar {
  height: 56px;
  padding: 0 24px;
  background: #fff;
  display: flex;
  align-items: center;
  justify-content: flex-end;
  border-bottom: 1px solid #e6eaf2;
}
</style>
