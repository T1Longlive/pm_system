import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import { defineComponent, h } from 'vue'
import type { UserItem } from '@/api/user'

/**
 * 人员管理页单测：列表加载、搜索/状态筛选、分页、新增与编辑、重置密码、启停。
 * el-table 在 jsdom 中不渲染行，行内操作直接调用 setup 暴露的处理函数；
 * el-select 在空串选项下会触发 jsdom 递归更新，故用桩件替代；
 * el-form 校验交给桩件控制（真实校验规则由 E2E 覆盖）。
 */

const listUsers = vi.hoisted(() => vi.fn())
const createUser = vi.hoisted(() => vi.fn())
const updateUser = vi.hoisted(() => vi.fn())
const resetPassword = vi.hoisted(() => vi.fn())
const updateUserStatus = vi.hoisted(() => vi.fn())
const validateMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/user', () => ({
  listUsers,
  createUser,
  updateUser,
  resetPassword,
  updateUserStatus
}))

import AdminUsers from './AdminUsers.vue'

const ElFormStub = defineComponent({
  name: 'ElForm',
  setup(_props, { expose, slots }) {
    expose({ validate: validateMock, clearValidate: () => {} })
    return () => h('div', slots.default?.())
  }
})

const rows: UserItem[] = [{
  id: 1,
  username: 'zhangsan',
  realName: '张三',
  isAdmin: 0,
  status: 1,
  createdAt: '2026-01-01 10:00:00'
}]

interface PageState {
  query: { keyword: string; status: '' | 0 | 1; page: number; size: number }
  form: { id: number; username: string; realName: string; password: string }
  openEdit: (row: UserItem) => void
  handleSubmit: () => Promise<void>
  handleResetPwd: (row: UserItem) => Promise<void>
  handleToggleStatus: (row: UserItem) => Promise<void>
}

function mountPage() {
  return mount(AdminUsers, {
    global: {
      plugins: [ElementPlus],
      stubs: { teleport: true, ElSelect: true, ElForm: ElFormStub }
    }
  })
}

const findButton = (wrapper: ReturnType<typeof mountPage>, text: string) =>
  wrapper.findAllComponents({ name: 'ElButton' }).find((item) => item.text() === text)!

const pageState = (wrapper: ReturnType<typeof mountPage>) =>
  (wrapper.vm.$ as never as { setupState: PageState }).setupState

beforeEach(() => {
  vi.restoreAllMocks()
  vi.clearAllMocks()
  vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
  vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
  validateMock.mockResolvedValue(true)
  listUsers.mockResolvedValue({ data: { data: { records: rows, total: 1 } } })
  createUser.mockResolvedValue({ data: { data: null } })
  updateUser.mockResolvedValue({ data: { data: null } })
  resetPassword.mockResolvedValue({ data: { data: null } })
  updateUserStatus.mockResolvedValue({ data: { data: null } })
})

describe('AdminUsers 人员管理页', () => {
  it('挂载时加载第一页人员列表', async () => {
    mountPage()
    await flushPromises()

    expect(listUsers).toHaveBeenCalledWith({ page: 1, size: 10 })
  })

  it('关键字搜索去空格并回到第一页', async () => {
    const wrapper = mountPage()
    await flushPromises()
    listUsers.mockClear()

    const input = wrapper.find('input[placeholder="搜索姓名 / 用户名"]')
    await input.setValue('  张  ')
    await input.trigger('keyup.enter')
    await flushPromises()

    expect(listUsers).toHaveBeenCalledWith({ page: 1, size: 10, keyword: '张' })
  })

  it('状态筛选为停用（0）时仍会传 status 参数', async () => {
    const wrapper = mountPage()
    await flushPromises()
    listUsers.mockClear()

    const statusSelect = wrapper.findAllComponents({ name: 'ElSelect' })[0]
    await statusSelect.vm.$emit('update:modelValue', 0)
    await statusSelect.vm.$emit('change', 0)
    await flushPromises()

    expect(listUsers).toHaveBeenCalledWith({ page: 1, size: 10, status: 0 })
  })

  it('分页：改页大小回到第一页，翻页查询当前页', async () => {
    const wrapper = mountPage()
    await flushPromises()
    const pagination = wrapper.findComponent({ name: 'ElPagination' })

    listUsers.mockClear()
    await pagination.vm.$emit('update:current-page', 2)
    await pagination.vm.$emit('current-change', 2)
    await flushPromises()
    expect(listUsers).toHaveBeenLastCalledWith({ page: 2, size: 10 })

    listUsers.mockClear()
    await pagination.vm.$emit('update:page-size', 20)
    await pagination.vm.$emit('size-change', 20)
    await flushPromises()
    expect(listUsers).toHaveBeenLastCalledWith({ page: 1, size: 20 })
  })

  it('新增人员：校验通过后提交，密码留空传 undefined 并回到第一页', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await findButton(wrapper, '新增人员').trigger('click')
    const dialog = wrapper.findAllComponents({ name: 'ElDialog' })[0]
    expect(dialog.props('modelValue')).toBe(true)
    expect(dialog.props('title')).toBe('新增人员')

    await wrapper.find('input[placeholder="3-30 位字母或数字"]').setValue('lisi')
    await wrapper.find('input[placeholder="请输入姓名"]').setValue('李四')
    await findButton(wrapper, '确定').trigger('click')
    await flushPromises()

    expect(createUser).toHaveBeenCalledWith({
      username: 'lisi',
      realName: '李四',
      password: undefined
    })
    expect(listUsers).toHaveBeenLastCalledWith({ page: 1, size: 10 })
    expect(dialog.props('modelValue')).toBe(false)
  })

  it('新增人员：表单校验不通过时不提交', async () => {
    validateMock.mockRejectedValue({})
    const wrapper = mountPage()
    await flushPromises()

    await findButton(wrapper, '新增人员').trigger('click')
    await findButton(wrapper, '确定').trigger('click')
    await flushPromises()

    expect(createUser).not.toHaveBeenCalled()
  })

  it('编辑人员：回填当前行，提交仅更新姓名', async () => {
    const wrapper = mountPage()
    await flushPromises()
    const state = pageState(wrapper)

    state.openEdit(rows[0])
    await flushPromises()
    const dialog = wrapper.findAllComponents({ name: 'ElDialog' })[0]
    expect(dialog.props('title')).toBe('编辑人员')
    expect(state.form.username).toBe('zhangsan')
    expect(wrapper.find('input[placeholder="3-30 位字母或数字"]').attributes('disabled')).toBeDefined()

    state.form.realName = '张三丰'
    await state.handleSubmit()
    await flushPromises()

    expect(updateUser).toHaveBeenCalledWith(1, { realName: '张三丰' })
  })

  it('重置密码：输入自定义密码后提交并刷新列表', async () => {
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: ' NewPass123 ' } as never)
    const wrapper = mountPage()
    await flushPromises()
    listUsers.mockClear()

    await pageState(wrapper).handleResetPwd(rows[0])
    await flushPromises()

    expect(resetPassword).toHaveBeenCalledWith(1, { password: 'NewPass123' })
    expect(listUsers).toHaveBeenCalledTimes(1)
  })

  it('停用账号：确认后按相反状态提交并刷新列表', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = mountPage()
    await flushPromises()
    listUsers.mockClear()

    await pageState(wrapper).handleToggleStatus(rows[0])
    await flushPromises()

    expect(updateUserStatus).toHaveBeenCalledWith(1, { status: 0 })
    expect(listUsers).toHaveBeenCalledTimes(1)
  })
})
