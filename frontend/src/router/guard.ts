import type { RouteMeta } from 'vue-router'

/**
 * 路由前置守卫的纯逻辑（从 router/index.ts 抽出，便于 Vitest 直接覆盖）。
 *
 * 规则顺序：
 * 1. pinia 有 token 但 localStorage 已被清 → 同步登出（401 过期/手动清除的自愈）
 * 2. 未登录访问非登录页 → 去登录页
 * 3. 有 token 但用户信息丢失（旧会话升级）→ 拉 /auth/me 回填
 * 4. meta.admin 页面：非管理员回工作台
 * 5. 强制改密用户：只能停留在改密页
 * 6. 无需改密却访问改密页：回工作台
 */

/** 守卫只需要的最小米路由信息（vue-router 的 RouteLocationNormalized 结构上兼容） */
export interface GuardRoute {
  path: string
  meta: RouteMeta
}

/** 守卫依赖的最小用户状态（Pinia store 结构上兼容） */
export interface GuardUserState {
  token: string
  userInfo: { isAdmin: number } | null
  forceChangePwd: boolean
  logout: () => void
  fetchUserInfo: () => Promise<unknown>
}

export type GuardResult = true | { path: string }

export async function authGuard(to: GuardRoute, userStore: GuardUserState): Promise<GuardResult> {
  // 自愈漂移：pinia 有 token 但 localStorage 已被清（401 过期/手动清除）→ 同步登出状态
  if (userStore.token && !localStorage.getItem('token')) {
    userStore.logout()
  }
  if (to.path !== '/login' && !userStore.token) {
    return { path: '/login' }
  }
  // 有 token 但用户信息丢失（如旧会话升级），调 /auth/me 回填
  if (to.path !== '/login' && userStore.token && !userStore.userInfo) {
    try {
      await userStore.fetchUserInfo()
    } catch {
      // 回填失败（token 失效等）由响应拦截器统一处理跳登录
    }
  }
  // 管理员专属页面（meta.admin）：非管理员重定向回工作台
  if (to.meta.admin && userStore.userInfo?.isAdmin !== 1) {
    return { path: '/dashboard' }
  }
  // 强制改密拦截：需改密用户只能停留在改密页（改密完成或登出前无法访问其他页面）
  if (to.path !== '/change-password' && userStore.token && userStore.forceChangePwd) {
    return { path: '/change-password' }
  }
  // 已无强制改密需求的用户访问改密页：回工作台
  if (to.path === '/change-password' && !userStore.forceChangePwd) {
    return { path: '/dashboard' }
  }
  return true
}
