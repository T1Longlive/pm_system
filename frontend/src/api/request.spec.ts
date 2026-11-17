import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { ElMessage } from 'element-plus'
import router from '@/router'
import request from './request'

vi.mock('@/router', () => ({
  default: { push: vi.fn() }
}))

interface Handler {
  fulfilled: (value: any) => any
  rejected: (error: any) => any
}

function interceptorHandlers(kind: 'request' | 'response'): Handler {
  const interceptors = kind === 'request' ? request.interceptors.request : request.interceptors.response
  return (interceptors as unknown as { handlers: Handler[] }).handlers[0]
}

beforeEach(() => {
  localStorage.clear()
  vi.clearAllMocks()
  vi.spyOn(ElMessage, 'error').mockImplementation(() => ({}) as any)
  vi.spyOn(ElMessage, 'warning').mockImplementation(() => ({}) as any)
})

afterEach(() => {
  vi.restoreAllMocks()
})

describe('请求拦截器', () => {
  it('有 token 时自动附加 Bearer 头', () => {
    localStorage.setItem('token', 'abc')
    const config = { headers: {} as Record<string, string> }

    const result = interceptorHandlers('request').fulfilled(config)

    expect(result.headers.Authorization).toBe('Bearer abc')
  })

  it('无 token 时不附加 Authorization', () => {
    const config = { headers: {} as Record<string, string> }

    const result = interceptorHandlers('request').fulfilled(config)

    expect(result.headers.Authorization).toBeUndefined()
  })
})

describe('响应拦截器', () => {
  it('业务成功原样返回响应', () => {
    const response = { data: { code: 200, message: 'success', data: { id: 1 } } }

    expect(interceptorHandlers('response').fulfilled(response)).toBe(response)
  })

  it('业务失败统一弹错并 reject', async () => {
    const response = { data: { code: 400, message: '参数错误', data: null } }

    await expect(interceptorHandlers('response').fulfilled(response))
      .rejects.toThrow('参数错误')
    expect(ElMessage.error).toHaveBeenCalledWith('参数错误')
  })

  it('业务 401 清除凭证并跳登录页', async () => {
    localStorage.setItem('token', 'expired')

    await expect(interceptorHandlers('response').fulfilled({
      data: { code: 401, message: '登录已过期', data: null }
    })).rejects.toThrow('登录已过期')

    expect(localStorage.getItem('token')).toBeNull()
    expect(router.push).toHaveBeenCalledWith('/login')
    expect(ElMessage.warning).toHaveBeenCalled()
  })

  it('HTTP 401 同样清除凭证并跳登录页', async () => {
    localStorage.setItem('token', 'expired')

    await expect(interceptorHandlers('response').rejected({
      response: { status: 401 }
    })).rejects.toBeTruthy()

    expect(localStorage.getItem('token')).toBeNull()
    expect(router.push).toHaveBeenCalledWith('/login')
  })

  it('非 401 的 HTTP 错误原样透传，不弹业务错误', async () => {
    const error = { response: { status: 500 } }

    await expect(interceptorHandlers('response').rejected(error)).rejects.toBe(error)
    expect(ElMessage.error).not.toHaveBeenCalled()
  })
})
