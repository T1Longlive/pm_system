import axios from 'axios'
import { ElMessage } from 'element-plus'
import router from '@/router'

// axios 实例：统一接口前缀 /api
const request = axios.create({
  baseURL: '/api',
  timeout: 15000
})

// 后端统一响应体：{code, message, data}
export interface ApiResponse<T = unknown> {
  code: number
  message: string
  data: T
}

// 请求拦截器：自动附带 localStorage 中的 token
request.interceptors.request.use((config) => {
  const token = localStorage.getItem('token')
  if (token) {
    config.headers.Authorization = `Bearer ${token}`
  }
  return config
})

// 401 提示去重时间戳（2s 窗口内并发 401 只弹一条）
let authTipAt = 0

// 401 统一处理：清除全部本地凭证并同步 pinia 状态，防止双源不一致导致守卫误判
//（只清 localStorage 不清 pinia 时，路由守卫读 pinia 仍认为已登录，会形成无限 401 循环）
const notifyAuthExpired = () => {
  localStorage.removeItem('token')
  localStorage.removeItem('userInfo')
  const now = Date.now()
  if (now - authTipAt > 2000) {
    authTipAt = now
    ElMessage.warning('登录已过期，请重新登录')
  }
  router.push('/login')
  // 动态 import 同步 pinia（避免与 stores/user 形成模块级循环依赖）
  import('@/stores/user')
    .then(({ useUserStore }) => {
      const userStore = useUserStore()
      userStore.token = ''
      userStore.userInfo = null
      userStore.forceChangePwd = false
    })
    .catch(() => {})
}

// 响应拦截器：统一处理业务状态码；未授权时清除凭证并跳转登录页
request.interceptors.response.use(
  (response) => {
    const body = response.data as ApiResponse | undefined
    // 仅处理含 code 字段的统一响应体
    if (body && typeof body === 'object' && typeof body.code === 'number') {
      // 后端 401：HTTP 200 + body code=401（登录过期），清除凭证并跳转登录页
      if (body.code === 401) {
        localStorage.removeItem('token')
        notifyAuthExpired()
        return Promise.reject(new Error(body.message || '登录已过期'))
      }
      // 业务失败（403 无权限、404 不存在等）：统一弹出后端错误信息，页面无需重复处理
      if (body.code !== 200) {
        const msg = body.message || '操作失败'
        ElMessage.error(msg)
        return Promise.reject(new Error(msg))
      }
    }
    return response
  },
  (error) => {
    if (error.response?.status === 401) {
      localStorage.removeItem('token')
      notifyAuthExpired()
    }
    return Promise.reject(error)
  }
)

export default request
