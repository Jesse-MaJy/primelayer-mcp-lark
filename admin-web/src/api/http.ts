import axios from 'axios'

export const http = axios.create({
  baseURL: '',
  timeout: 30000
})

http.interceptors.request.use((config) => {
  const token = localStorage.getItem('admin_token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

http.interceptors.response.use(
  (response) => {
    const body = response.data
    if (body && body.success === false) {
      return Promise.reject(new Error(body.error || '请求失败'))
    }
    return body?.data ?? body
  },
  (error) => {
    const body = error?.response?.data
    if (body?.error) {
      return Promise.reject(new Error(body.error))
    }
    if (body?.message) {
      return Promise.reject(new Error(body.message))
    }
    return Promise.reject(error)
  }
)
