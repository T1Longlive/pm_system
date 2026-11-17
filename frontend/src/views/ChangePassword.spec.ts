import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h } from 'vue'

/**
 * 改密页单测：表单校验（长度/一致性）、提交成功清理强制改密标记、错误提示与退出登录。
 * el-form 校验收敛为桩件控制（jsdom 下 Element Plus 校验依赖的 async-validator 行为不稳定）。
 */

const changePassword = vi.hoisted(() => vi.fn())
const push = vi.hoisted(() => vi.fn())
const validateMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/auth', () => ({
  login: vi.fn(),
  me: vi.fn(),
  changePassword
}))
vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return { ...actual, useRouter: () => ({ push }) }
})

import ChangePassword from './ChangePassword.vue'
import { useUserStore } from '@/stores/user'

const ElFormStub = defineComponent({
  name: 'ElForm',
  setup(_props, { expose, slots }) {
    expose({ validate: validateMock, clearValidate: () => {} })
    return () => h('div', slots.default?.())
  }
})

function mountPage() {
  setActivePinia(createPinia())
  const store = useUserStore()
  store.userInfo = {
    userId: 1,
    username: 'zhangsan',
    realName: '张三',
    isAdmin: 0,
    isPm: 0,
    forceChangePwd: 1
  }
  store.forceChangePwd = true
  return mount(ChangePassword, {
    global: {
      plugins: [ElementPlus],
      stubs: { Lock: true, Key: true, ElForm: ElFormStub }
    }
  })
}

async function fill(wrapper: ReturnType<typeof mountPage>, values: {
  oldPassword?: string
  newPassword?: string
  confirmPassword?: string
}) {
  if (values.oldPassword !== undefined) {
    await wrapper.find('input[placeholder="原密码"]').setValue(values.oldPassword)
  }
  if (values.newPassword !== undefined) {
    await wrapper.find('input[placeholder="新密码（至少 6 位）"]').setValue(values.newPassword)
  }
  if (values.confirmPassword !== undefined) {
    await wrapper.find('input[placeholder="确认新密码"]').setValue(values.confirmPassword)
  }
}

beforeEach(() => {
  vi.restoreAllMocks()
  vi.clearAllMocks()
  localStorage.clear()
  vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
  vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
  validateMock.mockResolvedValue(true)
  changePassword.mockResolvedValue({ data: { data: null } })
})

describe('ChangePassword 修改密码页', () => {
  it('展示当前账号姓名', () => {
    const wrapper = mountPage()

    expect(wrapper.text()).toContain('当前账号：张三')
  })

  it('新密码不足 6 位时不提交', async () => {
    validateMock.mockRejectedValue({})
    const wrapper = mountPage()
    await fill(wrapper, { oldPassword: 'Pm@123456', newPassword: '123', confirmPassword: '123' })
    await wrapper.find('.pwd-btn').trigger('click')
    await flushPromises()

    expect(changePassword).not.toHaveBeenCalled()
  })

  it('两次新密码不一致时不提交', async () => {
    validateMock.mockRejectedValue({})
    const wrapper = mountPage()
    await fill(wrapper, {
      oldPassword: 'Pm@123456',
      newPassword: 'new123456',
      confirmPassword: 'other123'
    })
    await wrapper.find('.pwd-btn').trigger('click')
    await flushPromises()

    expect(changePassword).not.toHaveBeenCalled()
  })

  it('提交成功：调用改密接口、清除强制改密标记并进入工作台', async () => {
    const wrapper = mountPage()
    await fill(wrapper, {
      oldPassword: 'Pm@123456',
      newPassword: 'new123456',
      confirmPassword: 'new123456'
    })
    await wrapper.find('.pwd-btn').trigger('click')
    await flushPromises()

    expect(changePassword).toHaveBeenCalledWith({
      oldPassword: 'Pm@123456',
      newPassword: 'new123456'
    })
    const store = useUserStore()
    expect(store.forceChangePwd).toBe(false)
    expect(store.userInfo?.forceChangePwd).toBe(0)
    expect(ElMessage.success).toHaveBeenCalledWith('密码修改成功')
    expect(push).toHaveBeenCalledWith('/dashboard')
  })

  it('提交失败：展示后端返回的错误信息', async () => {
    changePassword.mockRejectedValue({ response: { data: { message: '原密码错误' } } })
    const wrapper = mountPage()
    await fill(wrapper, {
      oldPassword: 'wrong',
      newPassword: 'new123456',
      confirmPassword: 'new123456'
    })
    await wrapper.find('.pwd-btn').trigger('click')
    await flushPromises()

    expect(ElMessage.error).toHaveBeenCalledWith('原密码错误')
    expect(push).not.toHaveBeenCalled()
  })

  it('暂不修改：退出登录并回到登录页', async () => {
    const wrapper = mountPage()
    await wrapper.find('.pwd-logout').trigger('click')

    const store = useUserStore()
    expect(store.token).toBe('')
    expect(store.userInfo).toBeNull()
    expect(push).toHaveBeenCalledWith('/login')
  })
})
