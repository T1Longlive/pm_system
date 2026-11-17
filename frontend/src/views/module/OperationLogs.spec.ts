import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'

/**
 * 操作日志页单测：下拉数据、筛选参数拼装、重置与分页。
 * 只 mock 三个接口，断言发出的查询参数与页面可见结果。
 */

const listProjects = vi.hoisted(() => vi.fn())
const pageOperationLogs = vi.hoisted(() => vi.fn())
const listLogOperators = vi.hoisted(() => vi.fn())

vi.mock('@/api/project', () => ({ listProjects }))
vi.mock('@/api/operationLogs', () => ({ pageOperationLogs, listLogOperators }))

import OperationLogs from './OperationLogs.vue'
import { useUserStore } from '@/stores/user'

const projects = [
  { id: 1, code: 'ACE', name: 'PM项目', description: '', creatorId: 1, creatorName: 'PM', memberCount: 1, myRole: ['PM'] },
  { id: 2, code: 'BDF', name: '成员项目', description: '', creatorId: 2, creatorName: 'PM', memberCount: 1, myRole: ['FE'] }
]

const logs = [{
  id: 1,
  bizType: 'REQUIREMENT',
  bizId: 5,
  bizNo: 'REQ-ACE-0005',
  bizName: '登录需求',
  projectId: 1,
  projectName: 'PM项目',
  action: 'CREATE',
  operatorId: 9,
  operatorName: '张三',
  remark: '创建需求',
  createdAt: '2026-01-01T10:00:00'
}]

function mountPage(isAdmin = true) {
  setActivePinia(createPinia())
  const store = useUserStore()
  store.userInfo = {
    userId: 1,
    username: 'user',
    realName: '用户',
    isAdmin: isAdmin ? 1 : 0,
    isPm: isAdmin ? 0 : 1,
    forceChangePwd: 0
  }
  return mount(OperationLogs, { global: { plugins: [ElementPlus] } })
}

const findButton = (wrapper: ReturnType<typeof mountPage>, text: string) =>
  wrapper.findAllComponents({ name: 'ElButton' }).find((item) => item.text() === text)!

beforeEach(() => {
  vi.clearAllMocks()
  listProjects.mockResolvedValue({ data: { data: { records: projects } } })
  listLogOperators.mockResolvedValue({
    data: { data: [{ id: 9, realName: '张三', username: 'zhangsan' }] }
  })
  pageOperationLogs.mockResolvedValue({
    data: { data: { records: logs, total: 1 } }
  })
})

describe('OperationLogs 操作日志页', () => {
  it('挂载时加载项目、操作人与第一页日志', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(listProjects).toHaveBeenCalledWith({ page: 1, size: 500 })
    expect(listLogOperators).toHaveBeenCalled()
    expect(pageOperationLogs).toHaveBeenCalledWith({ page: 1, size: 20 })
    expect(wrapper.text()).toContain('张三')
    expect(wrapper.text()).toContain('REQ-ACE-0005')
  })

  it('非管理员的项目下拉仅保留自己担任 PM 的项目', async () => {
    const wrapper = mountPage(false)
    await flushPromises()

    const labels = wrapper.findAllComponents({ name: 'ElOption' })
      .map((option) => option.props('label'))
      .filter((label): label is string => typeof label === 'string')
    expect(labels).toContain('PM项目')
    expect(labels).not.toContain('成员项目')
  })

  it('关键字查询去掉首尾空格且回到第一页', async () => {
    const wrapper = mountPage()
    await flushPromises()
    pageOperationLogs.mockClear()

    await wrapper.find('input[placeholder="备注关键词"]').setValue('  登录  ')
    await wrapper.find('input[placeholder="备注关键词"]').trigger('keyup.enter')
    await flushPromises()

    expect(pageOperationLogs).toHaveBeenCalledWith({ page: 1, size: 20, keyword: '登录' })
  })

  it('业务类型、日期范围筛选后点击查询会拼接对应参数', async () => {
    const wrapper = mountPage()
    await flushPromises()
    pageOperationLogs.mockClear()

    const bizTypeSelect = wrapper.findAllComponents({ name: 'ElSelect' })[0]
    await bizTypeSelect.vm.$emit('update:modelValue', 'BUG')
    await wrapper.findComponent({ name: 'ElDatePicker' })
      .vm.$emit('update:modelValue', ['2026-01-01', '2026-01-31'])
    await findButton(wrapper, '查询').trigger('click')
    await flushPromises()

    expect(pageOperationLogs).toHaveBeenCalledWith({
      page: 1,
      size: 20,
      bizType: 'BUG',
      startDate: '2026-01-01',
      endDate: '2026-01-31'
    })
  })

  it('操作人与项目筛选传数字 id', async () => {
    const wrapper = mountPage()
    await flushPromises()
    pageOperationLogs.mockClear()

    const selects = wrapper.findAllComponents({ name: 'ElSelect' })
    await selects[2].vm.$emit('update:modelValue', 9)
    await selects[3].vm.$emit('update:modelValue', 1)
    await findButton(wrapper, '查询').trigger('click')
    await flushPromises()

    expect(pageOperationLogs).toHaveBeenCalledWith({
      page: 1,
      size: 20,
      operatorId: 9,
      projectId: 1
    })
  })

  it('重置清空全部条件并从第一页重新查询', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.find('input[placeholder="备注关键词"]').setValue('登录')
    const bizTypeSelect = wrapper.findAllComponents({ name: 'ElSelect' })[0]
    await bizTypeSelect.vm.$emit('update:modelValue', 'REQUIREMENT')
    pageOperationLogs.mockClear()

    await findButton(wrapper, '重置').trigger('click')
    await flushPromises()

    expect(pageOperationLogs).toHaveBeenCalledWith({ page: 1, size: 20 })
  })

  it('分页：改页大小回到第一页，翻页查询当前页', async () => {
    const wrapper = mountPage()
    await flushPromises()
    const pagination = wrapper.findComponent({ name: 'ElPagination' })

    pageOperationLogs.mockClear()
    await pagination.vm.$emit('update:page-size', 50)
    await pagination.vm.$emit('size-change', 50)
    await flushPromises()
    expect(pageOperationLogs).toHaveBeenLastCalledWith({ page: 1, size: 50 })

    pageOperationLogs.mockClear()
    await pagination.vm.$emit('update:current-page', 3)
    await pagination.vm.$emit('current-change', 3)
    await flushPromises()
    expect(pageOperationLogs).toHaveBeenLastCalledWith({ page: 3, size: 50 })
  })
})
