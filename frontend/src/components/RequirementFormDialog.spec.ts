import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import { defineComponent, h } from 'vue'
import type { ProjectItem, ProjectMember } from '@/api/project'
import type { ReqAttachment, ReqDetail } from '@/api/requirement'
import type { ParticipantRow } from '@/utils/requirementForm'

/**
 * 需求新增/编辑弹窗单测：项目过滤、成员加载与策划默认值、参与人/附件整理、
 * 新增与编辑提交载荷、409 冲突、上传白名单与编辑器配置。
 *
 * jsdom 限制处理：
 * · el-form 校验由桩件控制（真实规则由 E2E 覆盖）；
 * · el-select / el-option 用可透传插槽的桩件，便于驱动 v-model 并断言候选；
 * · wangEditor 整体 mock，避免真实编辑器依赖 DOM 选区 API。
 */

const createRequirement = vi.hoisted(() => vi.fn())
const updateRequirement = vi.hoisted(() => vi.fn())
const getProject = vi.hoisted(() => vi.fn())
const uploadFile = vi.hoisted(() => vi.fn())
const validateMock = vi.hoisted(() => vi.fn())

vi.mock('@/api/requirement', () => ({ createRequirement, updateRequirement }))
vi.mock('@/api/project', () => ({ getProject }))
vi.mock('@/api/file', () => ({ uploadFile }))
vi.mock('@wangeditor/editor-for-vue', async () => {
  const { defineComponent, h } = await import('vue')
  const Editor = defineComponent({
    name: 'Editor',
    props: ['modelValue', 'defaultConfig', 'mode'],
    emits: ['update:modelValue', 'onCreated', 'onDestroyed'],
    setup() {
      return () => h('div', { class: 'editor-stub' })
    }
  })
  const Toolbar = defineComponent({
    name: 'Toolbar',
    props: ['editor', 'defaultConfig', 'mode'],
    setup() {
      return () => h('div', { class: 'toolbar-stub' })
    }
  })
  return { Editor, Toolbar }
})

import RequirementFormDialog from './RequirementFormDialog.vue'
import { useUserStore } from '@/stores/user'

const ElFormStub = defineComponent({
  name: 'ElForm',
  setup(_props, { expose, slots }) {
    expose({ validate: validateMock, clearValidate: () => {} })
    return () => h('div', { class: 'el-form-stub' }, slots.default?.())
  }
})

const ElSelectStub = defineComponent({
  name: 'ElSelect',
  props: ['modelValue', 'placeholder', 'multiple', 'collapseTags', 'filterable', 'clearable', 'loading', 'disabled'],
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

interface DialogState {
  form: { projectId?: number; title: string; priority: 1 | 2 | 3; plannerId?: number }
  attachments: ReqAttachment[]
  participantRows: ParticipantRow[]
  editorRef: { destroy: () => void } | undefined
  beforeUpload: (file: File) => boolean
  handleUploadRequest: (options: { file: File }) => Promise<void>
  handleRowCraftChange: (index: number) => void
  handleEditorCreated: (editor: { destroy: () => void }) => void
  handleEditorDestroyed: () => void
  editorConfig: {
    MENU_CONF: {
      uploadImage: {
        customUpload: (
          file: File,
          insert: (url: string, alt: string, href: string) => void
        ) => Promise<void>
      }
    }
  }
}

const projects: ProjectItem[] = [
  {
    id: 1, code: 'ACE', name: 'PM项目', description: '',
    creatorId: 9, creatorName: 'PM', memberCount: 2, myRole: ['PM']
  },
  {
    id: 2, code: 'BDF', name: '成员项目', description: '',
    creatorId: 10, creatorName: '成员', memberCount: 1, myRole: ['FE']
  }
]

const newProjectMembers: ProjectMember[] = [
  { userId: 9, realName: 'PM', username: 'pm', crafts: ['PM', 'PLANNER'] },
  { userId: 10, realName: '前端', username: 'fe', crafts: ['FE'] }
]

const editProjectMembers: ProjectMember[] = [
  { userId: 7, realName: '老王', username: 'lw', crafts: ['PM', 'PLANNER'] },
  { userId: 10, realName: '前端', username: 'fe', crafts: ['FE'] }
]

function makeEditData(overrides: Partial<ReqDetail> = {}): ReqDetail {
  return {
    id: 3,
    reqNo: 'REQ-BDF-0003',
    projectId: 3,
    projectName: '编辑项目',
    title: '旧需求',
    priority: 2,
    status: 'PENDING',
    plannerId: 7,
    plannerName: '老王',
    createdBy: 9,
    creatorName: '创建人',
    createdAt: '2026-01-01 00:00:00',
    updatedAt: 1700000000000,
    participants: [
      { userId: 7, realName: '老王', craft: 'PLANNER' },
      { userId: 10, realName: '前端', craft: 'FE' }
    ],
    craftSchedules: [],
    myRole: ['PM'],
    description: '<p>旧描述</p>',
    attachments: [
      { id: 5, url: '/upload/x.pdf', fileName: 'x.pdf', fileType: 'DOC', fileSize: 1024 }
    ],
    ...overrides
  }
}

function mountDialog(options: {
  modelValue?: boolean
  editData?: ReqDetail | null
  userId?: number
} = {}) {
  setActivePinia(createPinia())
  const store = useUserStore()
  store.userInfo = {
    userId: options.userId ?? 9,
    username: 'user',
    realName: '用户',
    isAdmin: 0,
    isPm: 0,
    forceChangePwd: 0
  }
  return mount(RequirementFormDialog, {
    props: {
      modelValue: options.modelValue ?? false,
      projects,
      editData: options.editData ?? null
    },
    global: {
      plugins: [ElementPlus],
      stubs: {
        teleport: true,
        ElForm: ElFormStub,
        ElSelect: ElSelectStub,
        ElOption: ElOptionStub,
        UploadFilled: true,
        Document: true,
        Close: true
      }
    }
  })
}

const dialogState = (wrapper: ReturnType<typeof mountDialog>) =>
  (wrapper.vm.$ as never as { setupState: DialogState }).setupState

const findButton = (wrapper: ReturnType<typeof mountDialog>, text: string) =>
  wrapper.findAllComponents({ name: 'ElButton' }).find((item) => item.text() === text)!

async function openDialog(wrapper: ReturnType<typeof mountDialog>) {
  await wrapper.setProps({ modelValue: true })
  await flushPromises()
}

beforeEach(() => {
  vi.restoreAllMocks()
  vi.clearAllMocks()
  localStorage.clear()
  vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
  vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
  validateMock.mockResolvedValue(true)
  createRequirement.mockResolvedValue({ data: { data: null } })
  updateRequirement.mockResolvedValue({ data: { data: null } })
  uploadFile.mockResolvedValue({
    data: {
      data: { url: '/upload/new.pdf', fileName: 'new.pdf', fileType: 'DOC', fileSize: 2048 }
    }
  })
  getProject.mockImplementation(async (id: number) => ({
    data: {
      data: {
        id,
        code: id === 3 ? 'BDF' : 'ACE',
        name: id === 3 ? '编辑项目' : 'PM项目',
        description: '',
        creatorId: id === 3 ? 7 : 9,
        creatorName: id === 3 ? '老王' : 'PM',
        createdAt: '2026-01-01 00:00:00',
        myRole: ['PM'],
        members: id === 3 ? editProjectMembers : newProjectMembers
      }
    }
  }))
})

describe('RequirementFormDialog 需求弹窗', () => {
  it('新增模式只展示可创建项目（PM/策划），未选项目时隐藏添加参与人', async () => {
    const wrapper = mountDialog()
    await openDialog(wrapper)

    expect(wrapper.text()).toContain('新增需求')
    const labels = wrapper.findAllComponents({ name: 'ElOption' })
      .map((option) => option.props('label'))
    expect(labels).toEqual(['PM项目'])
    expect(wrapper.text()).toContain('选择项目后可添加参与人')
  })

  it('新增提交：加载成员、默认策划为本人，并按契约提交参与人与附件', async () => {
    const wrapper = mountDialog()
    await openDialog(wrapper)
    const state = dialogState(wrapper)

    const projectSelect = wrapper.findAllComponents({ name: 'ElSelect' })[0]
    await projectSelect.vm.$emit('update:modelValue', 1)
    await projectSelect.vm.$emit('change', 1)
    await flushPromises()

    expect(getProject).toHaveBeenCalledWith(1)
    expect(state.form.plannerId).toBe(9)

    await wrapper.find('input[placeholder="请输入需求名称"]').setValue('  新需求  ')
    wrapper.findComponent({ name: 'Editor' }).vm.$emit('update:modelValue', '<p>描述</p>')
    state.participantRows.push({ craft: 'FE', userIds: [10] })
    state.attachments.push({
      id: 1, url: '/upload/a.pdf', fileName: 'a.pdf', fileType: 'DOC', fileSize: 2048
    })

    await findButton(wrapper, '确定').trigger('click')
    await flushPromises()

    expect(createRequirement).toHaveBeenCalledWith({
      projectId: 1,
      title: '新需求',
      priority: 2,
      description: '<p>描述</p>',
      plannerId: 9,
      participants: [{ userId: 10, craft: 'FE' }],
      attachments: [{
        url: '/upload/a.pdf', fileName: 'a.pdf', fileType: 'DOC', fileSize: 2048
      }]
    })
    expect(ElMessage.success).toHaveBeenCalledWith('需求创建成功')
    expect(wrapper.emitted('success')).toHaveLength(1)
    const updates = wrapper.emitted('update:modelValue')!
    expect(updates[updates.length - 1]).toEqual([false])
  })

  it('表单校验不通过时不发起创建请求', async () => {
    validateMock.mockRejectedValue({})
    const wrapper = mountDialog()
    await openDialog(wrapper)

    await findButton(wrapper, '确定').trigger('click')
    await flushPromises()

    expect(createRequirement).not.toHaveBeenCalled()
    expect(wrapper.emitted('success')).toBeUndefined()
  })

  it('编辑模式回显：项目只读、参与人按工种分组、附件与描述展示', async () => {
    const wrapper = mountDialog({ editData: makeEditData() })
    await openDialog(wrapper)
    const state = dialogState(wrapper)

    expect(wrapper.text()).toContain('编辑需求')
    expect(wrapper.text()).toContain('x.pdf')
    expect(wrapper.text()).toContain('1 KB')
    expect((wrapper.find('input[placeholder="请输入需求名称"]').element as HTMLInputElement).value)
      .toBe('旧需求')

    const disabledInput = wrapper.findAll('input').find((input) => input.element.disabled)
    expect(disabledInput, '编辑模式项目输入框应禁用').toBeTruthy()
    expect((disabledInput!.element as HTMLInputElement).value).toBe('编辑项目')

    expect(getProject).toHaveBeenCalledWith(3)
    expect(state.participantRows).toEqual([
      { craft: 'PLANNER', userIds: [7] },
      { craft: 'FE', userIds: [10] }
    ])
    const plannerLabels = wrapper.findAllComponents({ name: 'ElOption' })
      .map((option) => option.props('label'))
    expect(plannerLabels).toContain('老王')
  })

  it('编辑提交：带上 updatedAt，策划清空传 null，附件剔除本地 id', async () => {
    const wrapper = mountDialog({ editData: makeEditData() })
    await openDialog(wrapper)
    const state = dialogState(wrapper)

    await wrapper.find('input[placeholder="请输入需求名称"]').setValue(' 新标题 ')
    state.form.plannerId = undefined

    await findButton(wrapper, '确定').trigger('click')
    await flushPromises()

    expect(updateRequirement).toHaveBeenCalledWith(3, {
      title: '新标题',
      priority: 2,
      description: '<p>旧描述</p>',
      plannerId: null,
      participants: [
        { userId: 7, craft: 'PLANNER' },
        { userId: 10, craft: 'FE' }
      ],
      attachments: [{
        url: '/upload/x.pdf', fileName: 'x.pdf', fileType: 'DOC', fileSize: 1024
      }],
      updatedAt: 1700000000000
    })
    expect(ElMessage.success).toHaveBeenCalledWith('修改成功')
    expect(wrapper.emitted('success')).toHaveLength(1)
  })

  it('编辑提交遇到 409：关闭弹窗并通知父组件刷新详情', async () => {
    updateRequirement.mockRejectedValue(new Error('数据已被他人修改，请刷新后重试'))
    const wrapper = mountDialog({ editData: makeEditData() })
    await openDialog(wrapper)

    await findButton(wrapper, '确定').trigger('click')
    await flushPromises()

    expect(wrapper.emitted('conflict')).toHaveLength(1)
    expect(wrapper.emitted('success')).toBeUndefined()
    const updates = wrapper.emitted('update:modelValue')!
    expect(updates[updates.length - 1]).toEqual([false])
    expect(ElMessage.success).not.toHaveBeenCalled()
  })

  it('附件上传校验：类型白名单、50MB 上限与合法文件', async () => {
    const wrapper = mountDialog()
    await openDialog(wrapper)
    const state = dialogState(wrapper)

    expect(state.beforeUpload(new File(['x'], 'a.txt'))).toBe(false)
    expect(ElMessage.error).toHaveBeenCalledWith('仅支持 pdf / doc / docx / xlsx / zip 格式文档')

    const big = new File(['x'], 'big.pdf')
    Object.defineProperty(big, 'size', { value: 51 * 1024 * 1024 })
    expect(state.beforeUpload(big)).toBe(false)
    expect(ElMessage.error).toHaveBeenCalledWith('文档大小不超过 50MB')

    expect(state.beforeUpload(new File(['x'], 'ok.pdf'))).toBe(true)
  })

  it('附件上传成功后展示，点击可移除', async () => {
    const wrapper = mountDialog()
    await openDialog(wrapper)
    const state = dialogState(wrapper)

    await state.handleUploadRequest({ file: new File(['x'], 'new.pdf') })
    await flushPromises()

    expect(uploadFile).toHaveBeenCalledTimes(1)
    expect(wrapper.text()).toContain('new.pdf')
    expect(wrapper.text()).toContain('2 KB')

    await wrapper.find('.att-remove').trigger('click')
    expect(wrapper.text()).not.toContain('new.pdf')
    expect(dialogState(wrapper).attachments).toHaveLength(0)
  })

  it('参与人行动态增删：切换工种清空已选成员', async () => {
    const wrapper = mountDialog({ editData: makeEditData() })
    await openDialog(wrapper)
    const state = dialogState(wrapper)
    expect(state.participantRows).toHaveLength(2)

    const addButton = wrapper.findAllComponents({ name: 'ElButton' })
      .find((button) => button.text().includes('添加参与人'))!
    await addButton.trigger('click')
    expect(state.participantRows).toHaveLength(3)

    state.participantRows[2].craft = 'QA'
    state.participantRows[2].userIds = [10]
    state.handleRowCraftChange(2)
    expect(state.participantRows[2].userIds).toEqual([])

    const removeButton = wrapper.findAllComponents({ name: 'ElButton' })
      .find((button) => button.text() === '删除')!
    await removeButton.trigger('click')
    expect(state.participantRows).toHaveLength(2)
  })

  it('富文本图片上传：成功后插入图片，失败时不插入', async () => {
    const wrapper = mountDialog()
    await openDialog(wrapper)
    const customUpload = dialogState(wrapper).editorConfig.MENU_CONF.uploadImage.customUpload

    uploadFile.mockResolvedValue({
      data: { data: { url: '/upload/a.png', fileName: 'a.png', fileType: 'IMAGE', fileSize: 10 } }
    })
    const insertOk = vi.fn()
    await customUpload(new File(['x'], 'a.png'), insertOk)
    expect(insertOk).toHaveBeenCalledWith('/upload/a.png', 'a.png', '/upload/a.png')

    uploadFile.mockRejectedValue(new Error('boom'))
    const insertFail = vi.fn()
    await customUpload(new File(['x'], 'b.png'), insertFail)
    expect(insertFail).not.toHaveBeenCalled()
  })

  it('编辑器实例随弹窗关闭销毁', async () => {
    const wrapper = mountDialog()
    await openDialog(wrapper)
    const state = dialogState(wrapper)

    const editor = { destroy: vi.fn() }
    state.handleEditorCreated(editor)
    expect(state.editorRef).toBe(editor)

    await wrapper.setProps({ modelValue: false })
    await flushPromises()
    expect(editor.destroy).toHaveBeenCalled()

    state.handleEditorDestroyed()
    expect(state.editorRef).toBeUndefined()
  })

  it('关闭后重新打开：新增表单重置为空', async () => {
    const wrapper = mountDialog()
    await openDialog(wrapper)
    const state = dialogState(wrapper)

    await wrapper.find('input[placeholder="请输入需求名称"]').setValue('临时需求')
    state.attachments.push({
      id: 1, url: '/upload/t.pdf', fileName: 't.pdf', fileType: 'DOC', fileSize: 1
    })

    await wrapper.setProps({ modelValue: false })
    await flushPromises()
    await openDialog(wrapper)

    expect(dialogState(wrapper).form.title).toBe('')
    expect(dialogState(wrapper).attachments).toEqual([])
    expect(dialogState(wrapper).participantRows).toEqual([])
  })
})
