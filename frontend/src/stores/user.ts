import { defineStore } from 'pinia'
import {
  login as loginApi,
  me as meApi,
  type LoginParams,
  type UserInfo
} from '@/api/auth'

// 用户全局状态：token、用户信息、强制改密标记
export const useUserStore = defineStore('user', {
  state: () => ({
    // 登录凭证，优先从 localStorage 恢复（页面刷新不丢失）
    token: localStorage.getItem('token') || '',
    // 当前用户信息（优先从 localStorage 恢复，登录/刷新时更新）
    userInfo: restoreUserInfo(),
    // 是否强制修改密码（首次登录 / 管理员重置密码后），随 userInfo 持久化，刷新不丢失
    forceChangePwd: restoreUserInfo()?.forceChangePwd === 1
  }),
  getters: {
    // 是否已登录
    isLoggedIn: (state) => !!state.token
  },
  actions: {
    // 登录：保存 token 与用户信息到本地（res.data 为外层 body，业务数据在 data.data）
    async login(params: LoginParams) {
      const res = await loginApi(params)
      const result = res.data.data
      this.token = result.token
      this.forceChangePwd = result.forceChangePwd === 1
      this.userInfo = result
      localStorage.setItem('token', result.token)
      localStorage.setItem('userInfo', JSON.stringify(result))
    },
    // 拉取当前用户信息（页面刷新后回填），同步强制改密标记
    async fetchUserInfo() {
      const res = await meApi()
      this.userInfo = res.data.data
      this.forceChangePwd = res.data.data.forceChangePwd === 1
      localStorage.setItem('userInfo', JSON.stringify(res.data.data))
    },
    // 修改密码成功后清除强制改密标记
    markPwdChanged() {
      this.forceChangePwd = false
      if (this.userInfo) {
        this.userInfo.forceChangePwd = 0
        localStorage.setItem('userInfo', JSON.stringify(this.userInfo))
      }
    },
    // 登出：清空本地状态（跳转由调用方处理）
    logout() {
      this.token = ''
      this.userInfo = null
      this.forceChangePwd = false
      localStorage.removeItem('token')
      localStorage.removeItem('userInfo')
    }
  }
})

// 从 localStorage 恢复用户信息（损坏或不存在时返回 null）
function restoreUserInfo(): UserInfo | null {
  try {
    const raw = localStorage.getItem('userInfo')
    return raw ? (JSON.parse(raw) as UserInfo) : null
  } catch {
    return null
  }
}
