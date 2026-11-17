import { beforeEach, describe, expect, it, vi } from 'vitest'
import { authGuard, type GuardUserState } from './guard'

/**
 * 路由守卫纯逻辑测试：未登录、会话漂移自愈、用户信息回填、管理员页、强制改密五类分支。
 * 不创建真实 router，避免动态 import 视图组件。
 */

const route = (path: string, admin = false) => ({
  path,
  meta: admin ? { admin: true } : {}
})

function store(overrides: Partial<GuardUserState> = {}): GuardUserState {
  const user = {
    token: 'tk',
    userInfo: { isAdmin: 0 },
    forceChangePwd: false,
    fetchUserInfo: vi.fn().mockResolvedValue(undefined),
    ...overrides
  } as GuardUserState
  // 默认 logout 模拟真实 store：清空 token（守卫随后按「未登录」处理）
  if (!user.logout) {
    user.logout = vi.fn(() => {
      user.token = ''
    })
  }
  return user
}

beforeEach(() => {
  localStorage.clear()
  localStorage.setItem('token', 'tk')
  localStorage.setItem('userInfo', '{"isAdmin":0}')
})

describe('authGuard', () => {
  it('未登录访问业务页 → 重定向登录页', async () => {
    localStorage.clear()
    const user = store({ token: '' })

    expect(await authGuard(route('/requirements'), user)).toEqual({ path: '/login' })
  })

  it('未登录访问登录页 → 放行', async () => {
    localStorage.clear()

    expect(await authGuard(route('/login'), store({ token: '' }))).toBe(true)
  })

  it('pinia 有 token 但 localStorage 丢失 → 先登出再跳登录', async () => {
    localStorage.clear()
    const user = store({ token: 'tk' })

    expect(await authGuard(route('/dashboard'), user)).toEqual({ path: '/login' })
    expect(user.logout).toHaveBeenCalledTimes(1)
  })

  it('有 token 但用户信息缺失 → 回填后放行', async () => {
    const user = store({ userInfo: null })

    expect(await authGuard(route('/dashboard'), user)).toBe(true)
    expect(user.fetchUserInfo).toHaveBeenCalledTimes(1)
  })

  it('用户信息回填失败 → 不阻断，交由响应拦截器处理', async () => {
    const user = store({
      userInfo: null,
      fetchUserInfo: vi.fn().mockRejectedValue(new Error('401'))
    })

    expect(await authGuard(route('/dashboard'), user)).toBe(true)
    expect(user.fetchUserInfo).toHaveBeenCalledTimes(1)
  })

  it('管理员页面：非管理员回工作台，管理员放行', async () => {
    expect(await authGuard(route('/admin/users', true), store({ userInfo: { isAdmin: 0 } })))
      .toEqual({ path: '/dashboard' })
    expect(await authGuard(route('/admin/users', true), store({ userInfo: { isAdmin: 1 } })))
      .toBe(true)
  })

  it('强制改密用户访问其他页面 → 强制跳改密页', async () => {
    const user = store({ forceChangePwd: true })

    expect(await authGuard(route('/requirements'), user)).toEqual({ path: '/change-password' })
  })

  it('强制改密用户停留在改密页 → 放行', async () => {
    expect(await authGuard(route('/change-password'), store({ forceChangePwd: true }))).toBe(true)
  })

  it('无需改密却访问改密页 → 回工作台', async () => {
    expect(await authGuard(route('/change-password'), store({ forceChangePwd: false })))
      .toEqual({ path: '/dashboard' })
  })
})
