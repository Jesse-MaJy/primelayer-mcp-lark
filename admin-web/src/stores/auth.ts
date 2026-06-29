import { defineStore } from 'pinia'
import { adminApi } from '../api/admin'

export const useAuthStore = defineStore('auth', {
  state: () => ({
    token: localStorage.getItem('admin_token') || ''
  }),
  actions: {
    async login(username: string, password: string) {
      const response = await adminApi.login({ username, password })
      this.token = response.token
      localStorage.setItem('admin_token', response.token)
    },
    logout() {
      this.token = ''
      localStorage.removeItem('admin_token')
    }
  }
})
