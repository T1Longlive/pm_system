import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h } from 'vue'
import type { ProjectItem } from '@/api/project'

/**
 * 项目列表页单测：列表加载与卡片渲染、搜索/分页、管理员创建项目。
 * el-form 校验由桩件控制（真实规则由 E2E 覆盖）。
 */

const listProjects = vi.hoisted(() => vi.fn())
const createProject = vi.hoisted(() => vi.fn())
const push = vi.hoisted(() => vi.fn())
const validateMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/project', () => ({ listProjects, createProject }))
vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return { ...actual, useRouter: () => ({ push }) }
})
vi.mock('@/router', () => ({ default: { push } }))

import Projects from './Projects.vue'
import { useUserStore } from '@/stores/user'

const ElFormStub = defineComponent({
  name: 'ElForm',
  setup(_props, { expose, slots }) {
    expose({ validate: validateMock, clearValidate: () => {} })
    return () => h('div', { class: 'el-form-stub' }, slots.default?.())
  }
})

// 分页内部会挂载 el-select：jsdom 下其空值/尺寸联动会触发递归更新，桩件只保留事件契约
const ElPaginationStub = defineComponent({
  name: 'ElPagination',
  props: ['total', 'pageSizes', 'currentPage', 'pageSize', 'layout'],
  emits: ['update:current-page', 'update:page-size', 'size-change', 'current-change'],
  setup() {
    return () => h('div', { class: 'el-pagination-stub' })
  }
})

const rows: ProjectItem[] = [
  {
    id: 1, code: 'ACE', name: '示例项目', description: '项目描述',
    creatorId: 9, creatorName: 'PM', memberCount: 3, myRole: ['PM', 'FE']
  },
  {
    id: 2, code: 'BDF', name: '第二个项目', description: '',
    creatorId: 10, creatorName: '老王', memberCount: 1, myRole: ['QA']
  }
]

interface ProjectsState {
  query: { keyword: string; page: number; size: number }
  form: { name: string; code: string; description: string }
  dialogVisible: boolean
}

function mountPage(isAdmin = true) {
  setActivePinia(createPinia())
  const store = useUserStore()
  store.userInfo = {
    userId: 9,
    username: 'admin',
    realName: '管理员',
    isAdmin: isAdmin ? 1 : 0,
    isPm: 0,
    forceChangePwd: 0
  }
  return mount(Projects, {
    global: {
      plugins: [ElementPlus],
      stubs: { teleport: true, ElForm: ElFormStub, ElPagination: ElPaginationStub }
    }
  })
}

const pageState = (wrapper: ReturnType<typeof mountPage>) =>
  (wrapper.vm.$ as never as { setupState: ProjectsState }).setupState

const findButton = (wrapper: ReturnType<typeof mountPage>, text: string) =>
  wrapper.findAllComponents({ name: 'ElButton' }).find((item) => item.text() === text)!

beforeEach(() => {
  vi.restoreAllMocks()
  vi.clearAllMocks()
  vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
  vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
  validateMock.mockResolvedValue(true)
  listProjects.mockResolvedValue({ data: { data: { records: rows, total: 2 } } })
  createProject.mockResolvedValue({ data: { data: null } })
})

describe('Projects 项目列表页', () => {
  it('挂载加载第一页并渲染卡片信息与角色文案', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(listProjects).toHaveBeenCalledWith({ page: 1, size: 12 })
    expect(wrapper.findAll('.project-card')).toHaveLength(2)
    expect(wrapper.text()).toContain('示例项目')
    expect(wrapper.text()).toContain('ACE')
    expect(wrapper.text()).toContain('项目描述')
    expect(wrapper.text()).toContain('创建人：PM')
    expect(wrapper.text()).toContain('3 名成员')
    expect(wrapper.text()).toContain('项目经理')
    expect(wrapper.text()).toContain('前端开发')
  })

  it('管理员可见创建项目，普通成员不可见', async () => {
    const admin = mountPage()
    await flushPromises()
    expect(admin.text()).toContain('创建项目')

    const member = mountPage(false)
    await flushPromises()
    expect(member.text()).not.toContain('创建项目')
  })

  it('点击卡片进入项目详情', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.findAll('.project-card')[1].trigger('click')
    expect(push).toHaveBeenCalledWith('/projects/2')
  })

  it('关键字搜索去空格并回到第一页', async () => {
    const wrapper = mountPage()
    await flushPromises()
    listProjects.mockClear()

    const input = wrapper.find('input[placeholder="搜索项目名称"]')
    await input.setValue('  示例  ')
    await input.trigger('keyup.enter')
    await flushPromises()

    expect(listProjects).toHaveBeenCalledWith({ page: 1, size: 12, keyword: '示例' })
  })

  it('分页：改页大小回到第一页，翻页查询当前页', async () => {
    const wrapper = mountPage()
    await flushPromises()
    const pagination = wrapper.findComponent({ name: 'ElPagination' })

    listProjects.mockClear()
    await pagination.vm.$emit('update:current-page', 2)
    await pagination.vm.$emit('current-change', 2)
    await flushPromises()
    expect(listProjects).toHaveBeenLastCalledWith({ page: 2, size: 12 })

    listProjects.mockClear()
    await pagination.vm.$emit('update:page-size', 24)
    await pagination.vm.$emit('size-change', 24)
    await flushPromises()
    expect(listProjects).toHaveBeenLastCalledWith({ page: 1, size: 24 })
  })

  it('空列表展示空状态', async () => {
    listProjects.mockResolvedValue({ data: { data: { records: [], total: 0 } } })
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('暂无项目')
    expect(wrapper.findAll('.project-card')).toHaveLength(0)
  })

  it('创建项目：提交去空格后的表单并回到第一页刷新', async () => {
    const wrapper = mountPage()
    await flushPromises()
    listProjects.mockClear()

    await findButton(wrapper, '创建项目').trigger('click')
    expect(pageState(wrapper).dialogVisible).toBe(true)

    await wrapper.find('input[placeholder="请输入项目名称"]').setValue('  新项目  ')
    await wrapper.find('input[placeholder="2-6 位大写字母，如 ACE"]').setValue(' NEW ')
    await wrapper.find('textarea[placeholder="请输入项目描述（选填）"]').setValue(' 描述 ')
    await findButton(wrapper, '确定').trigger('click')
    await flushPromises()

    expect(createProject).toHaveBeenCalledWith({
      name: '新项目',
      code: 'NEW',
      description: '描述'
    })
    expect(ElMessage.success).toHaveBeenCalledWith('项目创建成功')
    expect(pageState(wrapper).dialogVisible).toBe(false)
    expect(listProjects).toHaveBeenLastCalledWith({ page: 1, size: 12 })
  })

  it('描述留空时不提交 description 字段', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await findButton(wrapper, '创建项目').trigger('click')
    await wrapper.find('input[placeholder="请输入项目名称"]').setValue('新项目')
    await wrapper.find('input[placeholder="2-6 位大写字母，如 ACE"]').setValue('NEW')
    await findButton(wrapper, '确定').trigger('click')
    await flushPromises()

    expect(createProject).toHaveBeenCalledWith({
      name: '新项目',
      code: 'NEW',
      description: undefined
    })
  })

  it('表单校验不通过时不提交创建请求', async () => {
    validateMock.mockRejectedValue({})
    const wrapper = mountPage()
    await flushPromises()

    await findButton(wrapper, '创建项目').trigger('click')
    await findButton(wrapper, '确定').trigger('click')
    await flushPromises()

    expect(createProject).not.toHaveBeenCalled()
    expect(pageState(wrapper).dialogVisible).toBe(true)
  })

  it('取消创建关闭弹窗且不调用接口', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await findButton(wrapper, '创建项目').trigger('click')
    await findButton(wrapper, '取消').trigger('click')
    await flushPromises()

    expect(createProject).not.toHaveBeenCalled()
    expect(pageState(wrapper).dialogVisible).toBe(false)
  })
})
