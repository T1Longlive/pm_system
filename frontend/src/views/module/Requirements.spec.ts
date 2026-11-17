import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'

/**
 * 需求列表页单测：页面编排（加载、筛选、页签、分页、权限显隐）。
 * 通过 Element Plus 真实组件挂载 + mock API/路由，避免对 el-table 的脆弱 DOM 断言；
 * 行内跳转与详情展示由 E2E 覆盖。
 */

const listRequirements = vi.hoisted(() => vi.fn())
const listProjects = vi.hoisted(() => vi.fn())
const push = vi.hoisted(() => vi.fn())

vi.mock('@/api/requirement', () => ({ listRequirements }))
vi.mock('@/api/project', () => ({ listProjects }))
// 只替换 useRouter，保留 createRouter 等导出（request.ts → router 会用到）
vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return { ...actual, useRouter: () => ({ push }) }
})
// request.ts 引入的是 '@/router' 单例，避免加载真实路由表
vi.mock('@/router', () => ({ default: { push } }))
vi.mock('@/components/RequirementFormDialog.vue', () => ({
  default: { name: 'RequirementFormDialog', template: '<div class="form-dialog-stub" />' }
}))
vi.mock('@/components/ProgressSteps.vue', () => ({
  default: { name: 'ProgressSteps', template: '<span class="steps-stub" />' }
}))

import Requirements from './Requirements.vue'
import { useUserStore } from '@/stores/user'

function mountPage(options: { isAdmin?: boolean; myRoles?: string[] } = {}) {
  setActivePinia(createPinia())
  const store = useUserStore()
  store.userInfo = {
    userId: 1,
    username: 'user',
    realName: '用户',
    isAdmin: options.isAdmin ? 1 : 0,
    isPm: 0,
    forceChangePwd: 0
  }
  listProjects.mockResolvedValue({
    data: {
      data: {
        records: options.myRoles
          ? [{
              id: 1,
              code: 'ACE',
              name: '示例项目',
              description: '',
              creatorId: 9,
              creatorName: 'PM',
              memberCount: 1,
              myRole: options.myRoles
            }]
          : []
      }
    }
  })
  return mount(Requirements, { global: { plugins: [ElementPlus] } })
}

beforeEach(() => {
  vi.clearAllMocks()
  listRequirements.mockResolvedValue({
    data: { data: { records: [], total: 0 } }
  })
})

describe('Requirements 需求列表页', () => {
  it('挂载即加载需求列表与项目下拉，管理员可见新增按钮', async () => {
    const wrapper = mountPage({ isAdmin: true })
    await flushPromises()

    expect(listRequirements).toHaveBeenCalledWith({ page: 1, size: 10, excludeTerminal: true })
    expect(listProjects).toHaveBeenCalledWith({ page: 1, size: 500 })
    expect(wrapper.find('.toolbar-add').exists()).toBe(true)
  })

  it('普通成员且无 PM/策划角色时隐藏新增按钮', async () => {
    const wrapper = mountPage({ isAdmin: false, myRoles: ['FE'] })
    await flushPromises()

    expect(wrapper.find('.toolbar-add').exists()).toBe(false)
  })

  it('关键字搜索：去空格、回到第一页并带上 keyword', async () => {
    const wrapper = mountPage({ isAdmin: true })
    await flushPromises()
    listRequirements.mockClear()

    const input = wrapper.find('input[placeholder="搜索需求编号 / 名称"]')
    await input.setValue(' 登录 ')
    await input.trigger('keyup.enter')
    await flushPromises()

    expect(listRequirements).toHaveBeenCalledWith({
      page: 1,
      size: 10,
      excludeTerminal: true,
      keyword: '登录'
    })
  })

  it('项目筛选：选中项目后带上 projectId 查询', async () => {
    const wrapper = mountPage({ isAdmin: true })
    await flushPromises()
    listRequirements.mockClear()

    const projectSelect = wrapper.findAllComponents({ name: 'ElSelect' })[0]
    await projectSelect.vm.$emit('update:modelValue', 7)
    await projectSelect.vm.$emit('change', 7)
    await flushPromises()

    expect(listRequirements).toHaveBeenCalledWith(
      expect.objectContaining({ projectId: 7, page: 1 })
    )
  })

  it('切到归档页签：仅按 ARCHIVED 查询，不受筛选条件影响', async () => {
    const wrapper = mountPage({ isAdmin: true })
    await flushPromises()
    listRequirements.mockClear()

    wrapper.findComponent({ name: 'ElTabs' }).vm.$emit('tab-change', 'archive')
    await flushPromises()

    expect(listRequirements).toHaveBeenCalledWith({ status: 'ARCHIVED', page: 1, size: 10 })
  })

  it('分页：翻页查询当前页，改页大小回到第一页', async () => {
    const wrapper = mountPage({ isAdmin: true })
    await flushPromises()
    const pagination = wrapper.findAllComponents({ name: 'ElPagination' })[0]

    listRequirements.mockClear()
    await pagination.vm.$emit('update:current-page', 3)
    await pagination.vm.$emit('current-change', 3)
    await flushPromises()
    expect(listRequirements).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 3, size: 10 })
    )

    listRequirements.mockClear()
    await pagination.vm.$emit('update:page-size', 20)
    await pagination.vm.$emit('size-change', 20)
    await flushPromises()
    expect(listRequirements).toHaveBeenLastCalledWith(
      expect.objectContaining({ page: 1, size: 20 })
    )
  })
})
