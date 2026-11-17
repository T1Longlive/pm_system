import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'

/**
 * Bug 列表页单测：项目选择、数据加载、页签刷新、添加行与按钮可用性。
 * vxe-table 不做真实渲染（虚拟滚动在 jsdom 里无意义），用桩件替代后断言页面编排与 API 调用。
 */

const listProjects = vi.hoisted(() => vi.fn())
const getProject = vi.hoisted(() => vi.fn())
const listBugs = vi.hoisted(() => vi.fn())
const createBug = vi.hoisted(() => vi.fn())

vi.mock('@/api/project', () => ({ listProjects, getProject }))
vi.mock('@/api/bug', () => ({
  listBugs,
  createBug,
  updateBug: vi.fn(),
  deleteBug: vi.fn(),
  archiveBug: vi.fn(),
  reopenBug: vi.fn(),
  addBugImages: vi.fn(),
  deleteBugImage: vi.fn()
}))
vi.mock('@/api/file', () => ({ uploadFile: vi.fn() }))
vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return { ...actual, useRoute: () => ({ query: {} }) }
})
vi.mock('@/router', () => ({ default: { push: vi.fn() } }))
vi.mock('vxe-table', () => ({
  VxeTable: { name: 'VxeTable', props: ['data'], template: '<div class="vxe-table-stub" />' },
  VxeColumn: { name: 'VxeColumn', template: '<div />' }
}))

import Bugs from './Bugs.vue'
import { useUserStore } from '@/stores/user'

const LAST_PROJECT_KEY = 'pm-bugs-last-project-id'

function mountPage() {
  setActivePinia(createPinia())
  const store = useUserStore()
  store.userInfo = {
    userId: 1,
    username: 'admin',
    realName: '管理员',
    isAdmin: 1,
    isPm: 0,
    forceChangePwd: 0
  }
  return mount(Bugs, {
    global: {
      plugins: [ElementPlus],
      // 图标由 main.ts 全局注册；单测里 stub 掉，避免未解析组件告警
      stubs: { Plus: true, VideoPlay: true, CircleCloseFilled: true }
    }
  })
}

async function chooseProject(wrapper: ReturnType<typeof mountPage>, projectId: number | '' = 1) {
  const select = wrapper.findAllComponents({ name: 'ElSelect' })[0]
  await select.vm.$emit('update:modelValue', projectId)
  await select.vm.$emit('change', projectId)
  await flushPromises()
}

beforeEach(() => {
  vi.clearAllMocks()
  localStorage.clear()
  listProjects.mockResolvedValue({
    data: {
      data: {
        records: [{
          id: 1, code: 'ACE', name: '示例项目', description: '',
          creatorId: 9, creatorName: 'PM', memberCount: 1, myRole: ['PM']
        }]
      }
    }
  })
  getProject.mockResolvedValue({ data: { data: { members: [] } } })
  listBugs.mockResolvedValue({ data: { data: { records: [], total: 0 } } })
  createBug.mockResolvedValue({ data: { data: { id: 9, bugNo: 'BUG-ACE-0001', images: [] } } })
})

describe('Bugs Bug 管理页', () => {
  it('未选择项目时显示引导，不请求 Bug 列表', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(wrapper.text()).toContain('请先选择项目')
    expect(listBugs).not.toHaveBeenCalled()
  })

  it('选择项目后加载总表与项目成员，并记住选择', async () => {
    const wrapper = mountPage()
    await flushPromises()
    await chooseProject(wrapper)

    expect(listBugs).toHaveBeenCalledWith({ projectId: 1, page: 1, size: 1000 }, 0)
    expect(getProject).toHaveBeenCalledWith(1)
    expect(localStorage.getItem(LAST_PROJECT_KEY)).toBe('1')
  })

  it('未选项目时「添加Bug」禁用；选择后点击会调用创建接口', async () => {
    const wrapper = mountPage()
    await flushPromises()

    const findAddButton = () => wrapper.findAllComponents({ name: 'ElButton' })
      .find((button) => button.text().includes('添加Bug'))!
    expect(findAddButton().attributes('disabled')).toBeDefined()

    await chooseProject(wrapper)
    await findAddButton().trigger('click')
    await flushPromises()

    expect(createBug).toHaveBeenCalledWith({ projectId: 1 })
  })

  it('切到归档页签：按 archived=1 重新查询', async () => {
    const wrapper = mountPage()
    await flushPromises()
    await chooseProject(wrapper)
    listBugs.mockClear()

    wrapper.findComponent({ name: 'ElTabs' }).vm.$emit('tab-change', 'archive')
    await flushPromises()

    expect(listBugs).toHaveBeenCalledWith({ projectId: 1, page: 1, size: 1000 }, 1)
  })

  it('清空项目选择时移除记忆并清空列表', async () => {
    const wrapper = mountPage()
    await flushPromises()
    await chooseProject(wrapper)
    expect(localStorage.getItem(LAST_PROJECT_KEY)).toBe('1')

    await chooseProject(wrapper, '')
    expect(localStorage.getItem(LAST_PROJECT_KEY)).toBeNull()
  })
})
