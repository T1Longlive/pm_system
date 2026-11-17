import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import type { ReqDetail } from '@/api/requirement'

/**
 * 需求详情页单测：数据加载、按角色/状态派生的按钮显隐、取消与归档动作、导航跳转。
 * 表单弹窗与进度条用桩件替代，避免 Element Plus 弹窗/富文本在 jsdom 下的耦合。
 */

const getRequirement = vi.hoisted(() => vi.fn())
const getRequirementLogs = vi.hoisted(() => vi.fn())
const cancelRequirement = vi.hoisted(() => vi.fn())
const archiveRequirement = vi.hoisted(() => vi.fn())
const push = vi.hoisted(() => vi.fn())

vi.mock('@/api/requirement', () => ({
  getRequirement,
  getRequirementLogs,
  cancelRequirement,
  archiveRequirement
}))
vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRoute: () => ({ params: { id: '5' } }),
    useRouter: () => ({ push })
  }
})
// request.ts 引入的是 '@/router' 单例，避免加载真实路由表
vi.mock('@/router', () => ({ default: { push } }))
vi.mock('@/components/ProgressSteps.vue', () => ({
  default: {
    name: 'ProgressSteps',
    props: ['status', 'participants', 'craftSchedules', 'size'],
    template: '<div class="steps-stub">{{ status }}</div>'
  }
}))
vi.mock('@/components/RequirementFormDialog.vue', () => ({
  default: {
    name: 'RequirementFormDialog',
    props: ['modelValue', 'projects', 'editData'],
    template: '<div class="form-dialog-stub" />'
  }
}))

import RequirementDetail from './RequirementDetail.vue'
import { useUserStore } from '@/stores/user'

function makeDetail(overrides: Partial<ReqDetail> = {}): ReqDetail {
  return {
    id: 5,
    reqNo: 'REQ-ACE-0005',
    projectId: 1,
    projectName: '示例项目',
    title: '登录需求',
    priority: 1,
    status: 'PENDING',
    plannerId: 9,
    plannerName: '策划',
    createdBy: 9,
    creatorName: '策划',
    createdAt: '2026-01-01 10:00:00',
    updatedAt: 1767223200000,
    participants: [
      { userId: 9, realName: '策划', craft: 'PLANNER' },
      { userId: 10, realName: '前端', craft: 'FE' }
    ],
    craftSchedules: [{ craft: 'FE', startDate: null, endDate: null }],
    myRole: ['PM'],
    description: '<p>描述内容</p>',
    attachments: [],
    ...overrides
  }
}

function mountPage(options: {
  detail?: Partial<ReqDetail>
  isAdmin?: boolean
  userId?: number
  logs?: boolean
} = {}) {
  setActivePinia(createPinia())
  const store = useUserStore()
  store.userInfo = {
    userId: options.userId ?? 9,
    username: 'user',
    realName: '用户',
    isAdmin: options.isAdmin === false ? 0 : 1,
    isPm: 0,
    forceChangePwd: 0
  }
  getRequirement.mockResolvedValue({ data: { data: makeDetail(options.detail) } })
  getRequirementLogs.mockResolvedValue({
    data: {
      data: options.logs === false
        ? []
        : [{
            id: 1,
            operatorName: '策划',
            action: 'CREATE',
            remark: '创建需求',
            roundNo: null,
            createdAt: '2026-01-01 10:00:00'
          }]
    }
  })
  return mount(RequirementDetail, {
    global: {
      plugins: [ElementPlus],
      stubs: { ArrowLeft: true, Document: true, Right: true }
    }
  })
}

const buttonTexts = (wrapper: ReturnType<typeof mountPage>) =>
  wrapper.findAllComponents({ name: 'ElButton' }).map((button) => button.text())

function findButton(wrapper: ReturnType<typeof mountPage>, text: string) {
  const button = wrapper.findAllComponents({ name: 'ElButton' })
    .find((item) => item.text() === text)
  expect(button, `应存在按钮「${text}」`).toBeTruthy()
  return button!
}

beforeEach(() => {
  vi.restoreAllMocks()
  vi.clearAllMocks()
  localStorage.clear()
  vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
  vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
})

describe('RequirementDetail 需求详情页', () => {
  it('管理员加载详情与操作日志，并渲染基本信息', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(getRequirement).toHaveBeenCalledWith(5)
    expect(getRequirementLogs).toHaveBeenCalledWith(5)
    expect(wrapper.text()).toContain('REQ-ACE-0005')
    expect(wrapper.text()).toContain('登录需求')
    expect(wrapper.text()).toContain('操作日志')
    expect(wrapper.text()).toContain('创建需求')
    expect(wrapper.findComponent({ name: 'ProgressSteps' }).props('status')).toBe('PENDING')
  })

  it('PM 且非终态：显示编辑与取消，不显示归档', async () => {
    const wrapper = mountPage()
    await flushPromises()

    const texts = buttonTexts(wrapper)
    expect(texts).toContain('编辑')
    expect(texts).toContain('取消需求')
    expect(texts).not.toContain('归档')
  })

  it('普通成员既非 PM 也非创建人：无编辑/取消，且不请求日志、不渲染日志卡片', async () => {
    const wrapper = mountPage({
      isAdmin: false,
      userId: 99,
      detail: { myRole: ['FE'], createdBy: 9 }
    })
    await flushPromises()

    const texts = buttonTexts(wrapper)
    expect(texts).not.toContain('编辑')
    expect(texts).not.toContain('取消需求')
    expect(getRequirementLogs).not.toHaveBeenCalled()
    expect(wrapper.text()).not.toContain('操作日志')
  })

  it('创建人非 PM：可编辑但不可取消', async () => {
    const wrapper = mountPage({
      isAdmin: false,
      userId: 9,
      detail: { myRole: ['FE'], createdBy: 9 }
    })
    await flushPromises()

    const texts = buttonTexts(wrapper)
    expect(texts).toContain('编辑')
    expect(texts).not.toContain('取消需求')
  })

  it('待归档状态仅 PM 显示归档按钮', async () => {
    const wrapper = mountPage({ detail: { status: 'ARCHIVING', myRole: ['PM'] } })
    await flushPromises()

    expect(buttonTexts(wrapper)).toContain('归档')
  })

  it('已归档终态：编辑/取消/归档全部隐藏', async () => {
    const wrapper = mountPage({ detail: { status: 'ARCHIVED', myRole: ['PM'] } })
    await flushPromises()

    const texts = buttonTexts(wrapper)
    expect(texts).not.toContain('编辑')
    expect(texts).not.toContain('取消需求')
    expect(texts).not.toContain('归档')
  })

  it('取消需求：填写原因后提交并刷新详情', async () => {
    vi.spyOn(ElMessageBox, 'prompt').mockResolvedValue({ value: '  不再需要  ' } as never)
    cancelRequirement.mockResolvedValue({ data: { data: null } })
    const wrapper = mountPage()
    await flushPromises()
    getRequirement.mockClear()

    await findButton(wrapper, '取消需求').trigger('click')
    await flushPromises()

    expect(cancelRequirement).toHaveBeenCalledWith(5, { reason: '不再需要' })
    expect(getRequirement).toHaveBeenCalledTimes(1)
    expect(ElMessage.success).toHaveBeenCalledWith('需求已取消')
  })

  it('归档需求：确认后调用归档接口并刷新详情', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    archiveRequirement.mockResolvedValue({ data: { data: null } })
    const wrapper = mountPage({ detail: { status: 'ARCHIVING' } })
    await flushPromises()
    getRequirement.mockClear()

    await findButton(wrapper, '归档').trigger('click')
    await flushPromises()

    expect(archiveRequirement).toHaveBeenCalledWith(5)
    expect(getRequirement).toHaveBeenCalledTimes(1)
    expect(ElMessage.success).toHaveBeenCalledWith('需求已归档')
  })

  it('点击编辑打开表单弹窗，弹窗成功/冲突均刷新详情', async () => {
    const wrapper = mountPage()
    await flushPromises()
    getRequirement.mockClear()

    await findButton(wrapper, '编辑').trigger('click')
    const dialog = wrapper.findComponent({ name: 'RequirementFormDialog' })
    expect(dialog.props('modelValue')).toBe(true)
    expect(dialog.props('editData')).toMatchObject({ id: 5 })

    dialog.vm.$emit('success')
    await flushPromises()
    expect(getRequirement).toHaveBeenCalledTimes(1)

    dialog.vm.$emit('conflict')
    await flushPromises()
    expect(getRequirement).toHaveBeenCalledTimes(2)
  })

  it('工具栏导航：返回列表、所属项目、排期管理', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await findButton(wrapper, '返回').trigger('click')
    expect(push).toHaveBeenCalledWith('/requirements')

    await wrapper.findComponent({ name: 'ElLink' }).trigger('click')
    expect(push).toHaveBeenCalledWith('/projects/1')

    await findButton(wrapper, '排期管理').trigger('click')
    expect(push).toHaveBeenCalledWith('/schedule')
  })

  it('附件展示大小，点击在新窗口打开', async () => {
    const open = vi.spyOn(window, 'open').mockImplementation(() => null)
    const wrapper = mountPage({
      detail: {
        attachments: [{
          id: 1,
          url: '/files/a.png',
          fileName: '截图.png',
          fileType: 'IMAGE',
          fileSize: 2048
        }]
      }
    })
    await flushPromises()

    expect(wrapper.text()).toContain('2 KB')
    await wrapper.find('.att-item').trigger('click')
    expect(open).toHaveBeenCalledWith('/files/a.png', '_blank')
  })
})
