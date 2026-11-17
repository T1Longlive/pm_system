import { beforeEach, describe, expect, it, vi } from 'vitest'
import { createPinia, setActivePinia } from 'pinia'
import { login as loginApi, me as meApi } from '@/api/auth'
import { useUserStore } from './user'

vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  me: vi.fn()
}))

const userInfo = {
  userId: 7,
  username: 'zhangsan',
  realName: '张三',
  isAdmin: 0,
  isPm: 0,
  forceChangePwd: 0
}

beforeEach(() => {
  localStorage.clear()
  setActivePinia(createPinia())
  vi.resetAllMocks()
})

describe('用户会话 store', () => {
  it('登录后写入 state 与 localStorage，并同步强制改密标记', async () => {
    vi.mocked(loginApi).mockResolvedValue({
      data: { code: 200, message: 'success', data: { ...userInfo, forceChangePwd: 1, token: 'tk-1' } }
    } as any)

    const store = useUserStore()
    await store.login({ username: 'zhangsan', password: 'pwd' })

    expect(store.token).toBe('tk-1')
    expect(store.userInfo?.realName).toBe('张三')
    expect(store.forceChangePwd).toBe(true)
    expect(localStorage.getItem('token')).toBe('tk-1')
    expect(JSON.parse(localStorage.getItem('userInfo') || '{}').userId).toBe(7)
  })

  it('刷新回填用户信息并同步强制改密标记', async () => {
    vi.mocked(meApi).mockResolvedValue({
      data: { code: 200, message: 'success', data: { ...userInfo, forceChangePwd: 1 } }
    } as any)

    const store = useUserStore()
    await store.fetchUserInfo()

    expect(store.userInfo?.userId).toBe(7)
    expect(store.forceChangePwd).toBe(true)
    expect(JSON.parse(localStorage.getItem('userInfo') || '{}').forceChangePwd).toBe(1)
  })

  it('改密成功后清除标记并持久化', () => {
    const store = useUserStore()
    store.userInfo = { ...userInfo, forceChangePwd: 1 }
    store.forceChangePwd = true

    store.markPwdChanged()

    expect(store.forceChangePwd).toBe(false)
    expect(store.userInfo?.forceChangePwd).toBe(0)
    expect(JSON.parse(localStorage.getItem('userInfo') || '{}').forceChangePwd).toBe(0)
  })

  it('登出清空 state 与 localStorage', async () => {
    vi.mocked(loginApi).mockResolvedValue({
      data: { code: 200, message: 'success', data: { ...userInfo, token: 'tk' } }
    } as any)
    const store = useUserStore()
    await store.login({ username: 'zhangsan', password: 'pwd' })

    store.logout()

    expect(store.token).toBe('')
    expect(store.userInfo).toBeNull()
    expect(store.forceChangePwd).toBe(false)
    expect(localStorage.getItem('token')).toBeNull()
    expect(localStorage.getItem('userInfo')).toBeNull()
  })

  it('从 localStorage 恢复会话', () => {
    localStorage.setItem('token', 'restored-token')
    localStorage.setItem('userInfo', JSON.stringify({ ...userInfo, forceChangePwd: 1 }))

    const store = useUserStore()

    expect(store.token).toBe('restored-token')
    expect(store.userInfo?.username).toBe('zhangsan')
    expect(store.forceChangePwd).toBe(true)
  })

  it('userInfo 损坏时降级为 null，不抛异常', () => {
    localStorage.setItem('userInfo', '{not-json')

    const store = useUserStore()

    expect(store.userInfo).toBeNull()
    expect(store.forceChangePwd).toBe(false)
  })
})
