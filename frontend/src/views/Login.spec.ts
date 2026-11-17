import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h } from 'vue'

/**
 * 登录页单测：表单校验、登录成功/强制改密分支、后端错误信息展示。
 * el-form 校验收敛为桩件控制（jsdom 下 Element Plus 校验依赖的 async-validator 行为不稳定）。
 */

const loginApi = vi.hoisted(() => vi.fn())
const push = vi.hoisted(() => vi.fn())
const validateMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/auth', () => ({
  login: loginApi,
  me: vi.fn(),
  changePassword: vi.fn()
}))
vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return { ...actual, useRouter: () => ({ push }) }
})

import Login from './Login.vue'
import { useUserStore } from '@/stores/user'

const loginResult = {
  token: 'token-1',
  userId: 1,
  username: 'admin',
  realName: '管理员',
  isAdmin: 1,
  isPm: 0,
  forceChangePwd: 0
}

const ElFormStub = defineComponent({
  name: 'ElForm',
  setup(_props, { expose, slots }) {
    expose({ validate: validateMock, clearValidate: () => {} })
    return () => h('div', slots.default?.())
  }
})

function mountPage() {
  setActivePinia(createPinia())
  return mount(Login, {
    global: {
      plugins: [ElementPlus],
      stubs: { User: true, Lock: true, ElForm: ElFormStub }
    }
  })
}

async function fillAndSubmit(wrapper: ReturnType<typeof mountPage>) {
  await wrapper.find('input[placeholder="用户名"]').setValue('admin')
  await wrapper.find('input[placeholder="密码"]').setValue('Pm@123456')
  await wrapper.find('.login-btn').trigger('click')
  await flushPromises()
}

beforeEach(() => {
  vi.restoreAllMocks()
  vi.clearAllMocks()
  localStorage.clear()
  vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
  vi.spyOn(ElMessage, 'warning').mockImplementation(() => undefined as never)
  vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
  validateMock.mockResolvedValue(true)
  loginApi.mockResolvedValue({ data: { data: loginResult } })
})

describe('Login 登录页', () => {
  it('表单为空时不发起登录请求', async () => {
    validateMock.mockRejectedValue({})
    const wrapper = mountPage()
    await wrapper.find('.login-btn').trigger('click')
    await flushPromises()

    expect(loginApi).not.toHaveBeenCalled()
    expect(push).not.toHaveBeenCalled()
  })

  it('登录成功：保存 token 并进入工作台', async () => {
    const wrapper = mountPage()
    await fillAndSubmit(wrapper)

    expect(loginApi).toHaveBeenCalledWith({ username: 'admin', password: 'Pm@123456' })
    expect(useUserStore().token).toBe('token-1')
    expect(ElMessage.success).toHaveBeenCalledWith('登录成功')
    expect(push).toHaveBeenCalledWith('/dashboard')
  })

  it('需要强制改密：提示后跳转改密页', async () => {
    loginApi.mockResolvedValue({
      data: { data: { ...loginResult, forceChangePwd: 1 } }
    })
    const wrapper = mountPage()
    await fillAndSubmit(wrapper)

    expect(ElMessage.warning).toHaveBeenCalledWith('首次登录或密码已被重置，请先修改密码')
    expect(push).toHaveBeenCalledWith('/change-password')
  })

  it('登录失败：优先展示后端返回的错误信息', async () => {
    loginApi.mockRejectedValue({ response: { data: { message: '账号已停用' } } })
    const wrapper = mountPage()
    await fillAndSubmit(wrapper)

    expect(ElMessage.error).toHaveBeenCalledWith('账号已停用')
    expect(push).not.toHaveBeenCalled()
  })

  it('登录失败且无后端信息时使用默认提示', async () => {
    loginApi.mockRejectedValue(new Error('network'))
    const wrapper = mountPage()
    await fillAndSubmit(wrapper)

    expect(ElMessage.error).toHaveBeenCalledWith('登录失败，请检查用户名或密码')
  })
})
