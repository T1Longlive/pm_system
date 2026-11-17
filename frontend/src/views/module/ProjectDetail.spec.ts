import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import { defineComponent, h } from 'vue'
import type { ProjectDetailData, ProjectMember } from '@/api/project'
import type { UserOption } from '@/api/user'

/**
 * 项目详情页单测：详情加载与 PM 权限显隐、编辑项目、添加成员（懒加载人员下拉）、
 * 移除工种 / 移除成员。
 * el-table 在 jsdom 中不渲染行，成员操作直接调用 setup 处理函数；el-form 校验由桩件控制。
 */

const getProject = vi.hoisted(() => vi.fn())
const updateProject = vi.hoisted(() => vi.fn())
const addProjectMember = vi.hoisted(() => vi.fn())
const removeProjectMember = vi.hoisted(() => vi.fn())
const removeProjectMemberCraft = vi.hoisted(() => vi.fn())
const userOptions = vi.hoisted(() => vi.fn())
const push = vi.hoisted(() => vi.fn())
const validateMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/project', () => ({
  getProject,
  updateProject,
  addProjectMember,
  removeProjectMember,
  removeProjectMemberCraft
}))
vi.mock('@/api/user', () => ({ userOptions }))
vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRoute: () => ({ params: { id: '3' } }),
    useRouter: () => ({ push })
  }
})
vi.mock('@/router', () => ({ default: { push } }))

import ProjectDetail from './ProjectDetail.vue'

const ElFormStub = defineComponent({
  name: 'ElForm',
  setup(_props, { expose, slots }) {
    expose({ validate: validateMock, clearValidate: () => {} })
    return () => h('div', { class: 'el-form-stub' }, slots.default?.())
  }
})

const ElSelectStub = defineComponent({
  name: 'ElSelect',
  props: ['modelValue', 'placeholder', 'filterable', 'loading'],
  emits: ['update:modelValue', 'change'],
  setup(_props, { slots }) {
    return () => h('div', { class: 'el-select-stub' }, slots.default?.())
  }
})

const ElOptionStub = defineComponent({
  name: 'ElOption',
  props: ['label', 'value'],
  setup(props) {
    return () => h('span', { class: 'el-option-stub' }, String(props.label ?? ''))
  }
})

const members: ProjectMember[] = [
  { userId: 9, realName: '张三', username: 'zhangsan', crafts: ['PLANNER', 'FE'] },
  { userId: 10, realName: '李四', username: 'lisi', crafts: ['QA'] }
]

function makeDetail(overrides: Partial<ProjectDetailData> = {}): ProjectDetailData {
  return {
    id: 3,
    code: 'ACE',
    name: '示例项目',
    description: '项目描述',
    creatorId: 9,
    creatorName: 'PM',
    createdAt: '2026-01-01 10:00:00',
    myRole: ['PM'],
    members: members.map((m) => ({ ...m, crafts: [...m.crafts] })),
    ...overrides
  }
}

interface DetailState {
  detail: ProjectDetailData | null
  isPM: boolean
  editVisible: boolean
  editForm: { name: string; description: string }
  addVisible: boolean
  addForm: { userId?: number; crafts: string[] }
  userOptionList: UserOption[]
  openAddMember: () => void
  handleRemoveCraft: (member: ProjectMember, craft: string) => Promise<void>
  handleRemoveMember: (member: ProjectMember) => Promise<void>
}

function mountPage(overrides: Partial<ProjectDetailData> = {}) {
  getProject.mockResolvedValue({ data: { data: makeDetail(overrides) } })
  return mount(ProjectDetail, {
    global: {
      plugins: [ElementPlus],
      stubs: {
        teleport: true,
        ElForm: ElFormStub,
        ElSelect: ElSelectStub,
        ElOption: ElOptionStub,
        ArrowLeft: true
      }
    }
  })
}

const pageState = (wrapper: ReturnType<typeof mountPage>) =>
  (wrapper.vm.$ as never as { setupState: DetailState }).setupState

const findButton = (wrapper: ReturnType<typeof mountPage>, text: string) =>
  wrapper.findAllComponents({ name: 'ElButton' }).find((item) => item.text() === text)!

const openDialog = (wrapper: ReturnType<typeof mountPage>) =>
  wrapper.findAllComponents({ name: 'ElDialog' }).find((dialog) => dialog.props('modelValue'))!

const dialogButton = (dialog: ReturnType<typeof openDialog>, text: string) =>
  dialog.findAllComponents({ name: 'ElButton' }).find((item) => item.text() === text)!

beforeEach(() => {
  vi.restoreAllMocks()
  vi.clearAllMocks()
  vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
  vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
  validateMock.mockResolvedValue(true)
  updateProject.mockResolvedValue({ data: { data: null } })
  addProjectMember.mockResolvedValue({ data: { data: null } })
  removeProjectMember.mockResolvedValue({ data: { data: null } })
  removeProjectMemberCraft.mockResolvedValue({ data: { data: null } })
  userOptions.mockResolvedValue({
    data: { data: [{ id: 5, realName: '张三', username: 'zhangsan' }] }
  })
})

describe('ProjectDetail 项目详情页', () => {
  it('加载详情并渲染基本信息；PM 可见编辑与添加成员', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(getProject).toHaveBeenCalledWith(3)
    const text = wrapper.text()
    expect(text).toContain('示例项目')
    expect(text).toContain('ACE')
    expect(text).toContain('PM')
    expect(text).toContain('项目描述')
    expect(text).toContain('2026-01-01 10:00:00')

    const texts = wrapper.findAllComponents({ name: 'ElButton' }).map((button) => button.text())
    expect(texts).toContain('编辑')
    expect(texts).toContain('添加成员')
  })

  it('非 PM：隐藏编辑与添加成员入口', async () => {
    const wrapper = mountPage({ myRole: ['FE'] })
    await flushPromises()

    const texts = wrapper.findAllComponents({ name: 'ElButton' }).map((button) => button.text())
    expect(pageState(wrapper).isPM).toBe(false)
    expect(texts).not.toContain('编辑')
    expect(texts).not.toContain('添加成员')
  })

  it('描述为空与无成员时展示占位文案', async () => {
    const wrapper = mountPage({ description: '', members: [] })
    await flushPromises()

    expect(wrapper.text()).toContain('暂无描述')
    expect(wrapper.text()).toContain('暂无成员')
  })

  it('返回按钮回到项目列表', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await findButton(wrapper, '返回').trigger('click')
    expect(push).toHaveBeenCalledWith('/projects')
  })

  it('编辑项目：回填并提交名称与描述，成功后刷新详情', async () => {
    const wrapper = mountPage()
    await flushPromises()
    getProject.mockClear()

    await findButton(wrapper, '编辑').trigger('click')
    const dialog = openDialog(wrapper)
    expect(dialog.props('title')).toBe('编辑项目')
    expect((dialog.find('input[placeholder="请输入项目名称"]').element as HTMLInputElement).value)
      .toBe('示例项目')

    await dialog.find('input[placeholder="请输入项目名称"]').setValue(' 新名称 ')
    await dialogButton(dialog, '确定').trigger('click')
    await flushPromises()

    expect(updateProject).toHaveBeenCalledWith(3, { name: '新名称', description: '项目描述' })
    expect(ElMessage.success).toHaveBeenCalledWith('修改成功')
    expect(pageState(wrapper).editVisible).toBe(false)
    expect(getProject).toHaveBeenCalledTimes(1)
  })

  it('编辑项目描述留空时不提交 description', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await findButton(wrapper, '编辑').trigger('click')
    const dialog = openDialog(wrapper)
    await dialog.find('textarea[placeholder="请输入项目描述（选填）"]').setValue('   ')
    await dialogButton(dialog, '确定').trigger('click')
    await flushPromises()

    expect(updateProject).toHaveBeenCalledWith(3, {
      name: '示例项目',
      description: undefined
    })
  })

  it('编辑校验不通过时不提交', async () => {
    validateMock.mockRejectedValue({})
    const wrapper = mountPage()
    await flushPromises()

    await findButton(wrapper, '编辑').trigger('click')
    const dialog = openDialog(wrapper)
    await dialogButton(dialog, '确定').trigger('click')
    await flushPromises()

    expect(updateProject).not.toHaveBeenCalled()
    expect(pageState(wrapper).editVisible).toBe(true)
  })

  it('添加成员：懒加载人员下拉，提交用户与多个工种并刷新', async () => {
    const wrapper = mountPage()
    await flushPromises()
    getProject.mockClear()

    await findButton(wrapper, '添加成员').trigger('click')
    await flushPromises()
    expect(userOptions).toHaveBeenCalledTimes(1)

    const dialog = openDialog(wrapper)
    expect(dialog.props('title')).toBe('添加成员')
    const optionLabels = wrapper.findAllComponents({ name: 'ElOption' })
      .map((option) => option.props('label'))
    expect(optionLabels).toContain('张三（zhangsan）')

    const state = pageState(wrapper)
    state.addForm.userId = 5
    state.addForm.crafts = ['FE', 'QA']
    await dialogButton(dialog, '确定').trigger('click')
    await flushPromises()

    expect(addProjectMember).toHaveBeenCalledWith(3, { userId: 5, crafts: ['FE', 'QA'] })
    expect(ElMessage.success).toHaveBeenCalledWith('添加成功')
    expect(state.addVisible).toBe(false)
    expect(getProject).toHaveBeenCalledTimes(1)
  })

  it('人员选项已加载时再次打开不再请求', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await findButton(wrapper, '添加成员').trigger('click')
    await flushPromises()
    expect(userOptions).toHaveBeenCalledTimes(1)

    const dialog = openDialog(wrapper)
    await dialogButton(dialog, '取消').trigger('click')
    await flushPromises()

    await findButton(wrapper, '添加成员').trigger('click')
    await flushPromises()
    expect(userOptions).toHaveBeenCalledTimes(1)
  })

  it('添加成员校验不通过时不提交', async () => {
    validateMock.mockRejectedValue({})
    const wrapper = mountPage()
    await flushPromises()

    await findButton(wrapper, '添加成员').trigger('click')
    await flushPromises()
    const dialog = openDialog(wrapper)
    await dialogButton(dialog, '确定').trigger('click')
    await flushPromises()

    expect(addProjectMember).not.toHaveBeenCalled()
    expect(pageState(wrapper).addVisible).toBe(true)
  })

  it('移除单个工种：确认后调用接口并刷新详情', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = mountPage()
    await flushPromises()
    getProject.mockClear()

    await pageState(wrapper).handleRemoveCraft(members[0], 'FE')
    await flushPromises()

    expect(ElMessageBox.confirm).toHaveBeenCalledWith(
      '确认移除「张三」的「前端开发」工种？',
      '提示',
      expect.objectContaining({ type: 'warning' })
    )
    expect(removeProjectMemberCraft).toHaveBeenCalledWith(3, 9, 'FE')
    expect(ElMessage.success).toHaveBeenCalledWith('已移除工种')
    expect(getProject).toHaveBeenCalledTimes(1)
  })

  it('移除整个成员：确认后调用接口并刷新详情', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = mountPage()
    await flushPromises()
    getProject.mockClear()

    await pageState(wrapper).handleRemoveMember(members[1])
    await flushPromises()

    expect(removeProjectMember).toHaveBeenCalledWith(3, 10)
    expect(ElMessage.success).toHaveBeenCalledWith('已移除成员')
    expect(getProject).toHaveBeenCalledTimes(1)
  })

  it('取消移除确认时不调用接口', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockRejectedValue(new Error('cancel'))
    const wrapper = mountPage()
    await flushPromises()

    await pageState(wrapper).handleRemoveMember(members[0])
    await flushPromises()

    expect(removeProjectMember).not.toHaveBeenCalled()
    expect(ElMessage.success).not.toHaveBeenCalled()
  })
})
