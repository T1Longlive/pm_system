import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'

/**
 * 工作台页单测：三接口并行加载、统计卡片与通知/需求渲染、各跳转入口。
 */

const getDashboardSummary = vi.hoisted(() => vi.fn())
const listMyRequirements = vi.hoisted(() => vi.fn())
const listNotifications = vi.hoisted(() => vi.fn())
const push = vi.hoisted(() => vi.fn())

vi.mock('@/api/dashboard', () => ({ getDashboardSummary, listMyRequirements }))
vi.mock('@/api/notification', () => ({ listNotifications }))
vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return { ...actual, useRouter: () => ({ push }) }
})

import Dashboard from './Dashboard.vue'

const summary = {
  unreadNotifications: 3,
  myRequirementCount: 2,
  monthEffort: 8,
  openBugCount: 1
}
const myReqs = [{
  id: 5,
  reqNo: 'REQ-ACE-0005',
  title: '登录需求',
  projectId: 1,
  projectName: 'A项目',
  status: 'DEV',
  priority: 1
}]
const notifications = [
  {
    id: 1,
    type: 'REQ_UPDATE',
    title: '需求更新',
    content: '需求有新的排期',
    bizType: 'REQUIREMENT',
    bizId: 5,
    isRead: 0 as const,
    createdAt: '2026-01-01T10:00:00'
  },
  {
    id: 2,
    type: 'BUG_ASSIGNED',
    title: 'Bug 指派',
    content: '有一条新 Bug',
    bizType: 'BUG',
    bizId: 9,
    isRead: 1 as const,
    createdAt: '2026-01-02T11:30:00'
  }
]

function mountPage() {
  return mount(Dashboard, { global: { plugins: [ElementPlus] } })
}

beforeEach(() => {
  vi.restoreAllMocks()
  vi.clearAllMocks()
  vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
  getDashboardSummary.mockResolvedValue({ data: { data: summary } })
  listMyRequirements.mockResolvedValue({ data: { data: myReqs } })
  listNotifications.mockResolvedValue({ data: { data: { records: notifications, total: 2 } } })
})

describe('Dashboard 工作台页', () => {
  it('并行加载汇总、我的需求与通知并渲染', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(getDashboardSummary).toHaveBeenCalledTimes(1)
    expect(listMyRequirements).toHaveBeenCalledTimes(1)
    expect(listNotifications).toHaveBeenCalledWith({ page: 1, size: 8 })
    expect(wrapper.findAll('.stat-value').map((el) => el.text())).toEqual(['3', '2', '8', '1'])
    expect(wrapper.text()).toContain('登录需求')
    expect(wrapper.text()).toContain('需求更新')
    expect(wrapper.text()).toContain('Bug 指派')
    // 仅未读通知显示红点
    expect(wrapper.findAll('.notif-dot')).toHaveLength(1)
  })

  it('点击可跳转的统计卡片进入对应页面，未读通知卡片不跳转', async () => {
    const wrapper = mountPage()
    await flushPromises()
    const cards = wrapper.findAllComponents({ name: 'ElCard' })
    const requirementCard = cards.find((card) => card.text().includes('参与需求'))!

    await requirementCard.trigger('click')
    expect(push).toHaveBeenCalledWith('/requirements')

    const unreadCard = cards.find((card) => card.text().includes('未读通知'))!
    push.mockClear()
    await unreadCard.trigger('click')
    expect(push).not.toHaveBeenCalled()
  })

  it('通知按业务类型跳转：需求详情 / Bug 管理', async () => {
    const wrapper = mountPage()
    await flushPromises()
    const items = wrapper.findAll('.notif-item')

    await items[0].trigger('click')
    expect(push).toHaveBeenCalledWith('/requirements/5')

    await items[1].trigger('click')
    expect(push).toHaveBeenCalledWith('/bugs')
  })

  it('快捷入口跳转对应路由', async () => {
    const wrapper = mountPage()
    await flushPromises()

    const button = wrapper.findAllComponents({ name: 'ElButton' })
      .find((item) => item.text() === '统计报表')!
    await button.trigger('click')

    expect(push).toHaveBeenCalledWith('/reports')
  })

  it('接口失败时逐个提示错误', async () => {
    getDashboardSummary.mockRejectedValue(new Error('boom'))
    listMyRequirements.mockRejectedValue(new Error('boom'))
    listNotifications.mockRejectedValue(new Error('boom'))
    const wrapper = mountPage()
    await flushPromises()

    expect(ElMessage.error).toHaveBeenCalledWith('工作台汇总数据加载失败')
    expect(ElMessage.error).toHaveBeenCalledWith('我的需求加载失败')
    expect(ElMessage.error).toHaveBeenCalledWith('通知加载失败')
    expect(wrapper.text()).toContain('暂无通知')
  })
})
