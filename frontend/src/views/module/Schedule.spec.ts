import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage, ElMessageBox } from 'element-plus'
import { createPinia, setActivePinia } from 'pinia'
import type { ScheduleMonth, ScheduleRowData, ScheduleView } from '@/api/schedule'

/**
 * 排期页单测：月表加载与默认选中、权限显隐、行保存（成功/超额回退/顺延/409）、
 * 整需求复制与移除、生成下月、需求池回填、离开拦截。
 *
 * vxe-table 用暴露表格方法的桩件替代；行级操作直接调用 setup 处理函数（矩阵 DOM 由 E2E 覆盖）。
 */

const listProjects = vi.hoisted(() => vi.fn())
const listScheduleMonths = vi.hoisted(() => vi.fn())
const createScheduleMonth = vi.hoisted(() => vi.fn())
const getScheduleMonthView = vi.hoisted(() => vi.fn())
const saveScheduleRow = vi.hoisted(() => vi.fn())
const copyRequirementToNextMonth = vi.hoisted(() => vi.fn())
const removeRequirementFromMonth = vi.hoisted(() => vi.fn())
const listIncompleteRequirements = vi.hoisted(() => vi.fn())
const push = vi.hoisted(() => vi.fn())
const routeLeaveHandlers = vi.hoisted(
  () => [] as Array<(to: { path: string }) => Promise<boolean | void>>
)

vi.mock('@/api/project', () => ({ listProjects }))
vi.mock('@/api/schedule', () => ({
  listScheduleMonths,
  createScheduleMonth,
  getScheduleMonthView,
  saveScheduleRow,
  copyRequirementToNextMonth,
  removeRequirementFromMonth,
  listIncompleteRequirements
}))
vi.mock('vue-router', async (importOriginal) => {
  const actual = await importOriginal<typeof import('vue-router')>()
  return {
    ...actual,
    useRouter: () => ({ push }),
    onBeforeRouteLeave: (fn: (to: { path: string }) => Promise<boolean | void>) => {
      routeLeaveHandlers.push(fn)
    }
  }
})
vi.mock('@/router', () => ({ default: { push } }))
vi.mock('@/components/ScheduleRequirementPicker.vue', () => ({
  default: {
    name: 'ScheduleRequirementPicker',
    props: ['modelValue', 'monthId', 'existingIds'],
    template: '<div class="picker-stub" />'
  }
}))
vi.mock('@/components/ScheduleFilterHeader.vue', () => ({
  default: { name: 'ScheduleFilterHeader', props: ['title', 'active'], template: '<div />' }
}))
vi.mock('vxe-table', async () => {
  const { defineComponent, h } = await import('vue')
  const VxeTable = defineComponent({
    name: 'VxeTable',
    setup(_props, { expose }) {
      expose({
        refreshColumn: () => {},
        setMergeCells: () => {},
        clearMergeCells: () => {}
      })
      return () => h('div', { class: 'vxe-table-stub' })
    }
  })
  const VxeColumn = defineComponent({
    name: 'VxeColumn',
    setup() {
      return () => h('div')
    }
  })
  return { VxeTable, VxeColumn }
})

import Schedule from './Schedule.vue'
import { useUserStore } from '@/stores/user'

const now = new Date()
const YEAR = now.getFullYear()
const MONTH = now.getMonth() + 1
const MONTH_ID = 7
const pad = (n: number) => String(n).padStart(2, '0')
const DAY = `${YEAR}-${pad(MONTH)}-02`
const DAY_OFF = `${YEAR}-${pad(MONTH)}-03`

function makeMonth(id: number, year = YEAR, month = MONTH): ScheduleMonth {
  return { id, year, month }
}

function makeView(monthId = MONTH_ID): ScheduleView {
  return {
    month: makeMonth(monthId),
    days: [
      { day: DAY, type: 'WORKDAY', name: null },
      { day: DAY_OFF, type: 'WEEKEND', name: null }
    ],
    groups: [{
      seq: 1,
      requirementId: 5,
      reqNo: 'REQ-ACE-0005',
      title: '测试需求',
      projectId: 1,
      projectName: 'A项目',
      plannerName: '策划',
      priority: 1,
      reqStatus: 'PENDING',
      rows: [{
        rowId: 11,
        craft: 'FE',
        userId: 9,
        realName: '前端',
        status: 'TODO',
        isOverflow: 0,
        plannedEffort: 2,
        scheduledSum: 1,
        updatedAt: 100,
        cells: { [DAY]: 1 }
      }]
    }]
  }
}

interface FlatRowLike {
  _key: string
  seq: number
  requirementId: number
  title: string
  projectId: number
  reqStatus: string
  row: ScheduleRowData
}

interface ScheduleState {
  activeMonthId: number | null
  months: ScheduleMonth[]
  flatRows: FlatRowLike[]
  canManage: boolean
  cellEditable: (row: FlatRowLike) => boolean
  handleEffortChange: (row: FlatRowLike) => void
  handleCopy: (row: FlatRowLike) => Promise<void>
  handleRemove: (row: FlatRowLike) => Promise<void>
  handleCreateNextMonth: () => Promise<void>
  handleAdded: () => void
}

function mountPage(options: { isAdmin?: boolean; pmProject?: boolean } = {}) {
  setActivePinia(createPinia())
  const store = useUserStore()
  store.userInfo = {
    userId: 9,
    username: 'user',
    realName: '用户',
    isAdmin: options.isAdmin === false ? 0 : 1,
    isPm: options.pmProject === false ? 0 : 1,
    forceChangePwd: 0
  }
  listProjects.mockResolvedValue({
    data: {
      data: {
        records: [{
          id: 1, code: 'ACE', name: 'A项目', description: '',
          creatorId: 9, creatorName: 'PM', memberCount: 1,
          myRole: options.pmProject === false ? ['FE'] : ['PM']
        }]
      }
    }
  })
  // 离开守卫以 localStorage token 判断登录态
  localStorage.setItem('token', 'unit-token')
  return mount(Schedule, {
    global: { plugins: [ElementPlus], stubs: { teleport: true } }
  })
}

const pageState = (wrapper: ReturnType<typeof mountPage>) =>
  (wrapper.vm.$ as never as { setupState: ScheduleState }).setupState

const buttonTexts = (wrapper: ReturnType<typeof mountPage>) =>
  wrapper.findAllComponents({ name: 'ElButton' }).map((button) => button.text())

const findButton = (wrapper: ReturnType<typeof mountPage>, text: string) =>
  wrapper.findAllComponents({ name: 'ElButton' }).find((item) => item.text() === text)!

beforeEach(() => {
  vi.restoreAllMocks()
  vi.clearAllMocks()
  localStorage.clear()
  routeLeaveHandlers.length = 0
  vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
  vi.spyOn(ElMessage, 'warning').mockImplementation(() => undefined as never)
  vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
  listScheduleMonths.mockResolvedValue({ data: { data: [makeMonth(MONTH_ID)] } })
  getScheduleMonthView.mockImplementation(async (id: number) => ({ data: { data: makeView(id) } }))
  saveScheduleRow.mockResolvedValue({ data: { data: { updatedAt: 200, overflow: null } } })
  createScheduleMonth.mockResolvedValue({ data: { data: makeMonth(8) } })
  copyRequirementToNextMonth.mockResolvedValue({
    data: { data: { monthId: 8, duplicated: true, insertedCount: 1 } }
  })
  removeRequirementFromMonth.mockResolvedValue({ data: { data: 1 } })
  listIncompleteRequirements.mockResolvedValue({ data: { data: [] } })
})

describe('Schedule 排期页', () => {
  it('挂载即加载项目、月表与视图，管理员可见全部操作入口', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(listProjects).toHaveBeenCalledWith({ page: 1, size: 500 })
    expect(listScheduleMonths).toHaveBeenCalled()
    expect(getScheduleMonthView).toHaveBeenCalledWith(MONTH_ID)
    expect(pageState(wrapper).activeMonthId).toBe(MONTH_ID)
    expect(wrapper.text()).toContain(`${YEAR}年${MONTH}月`)

    const texts = buttonTexts(wrapper)
    expect(texts).toContain('生成下月排期表')
    expect(texts).toContain('从需求池添加')
    expect(texts).toContain('日历管理')
  })

  it('没有任何月表时展示引导，且不请求视图', async () => {
    listScheduleMonths.mockResolvedValue({ data: { data: [] } })
    const wrapper = mountPage()
    await flushPromises()

    expect(getScheduleMonthView).not.toHaveBeenCalled()
    expect(wrapper.text()).toContain('暂无排期表')
    expect(findButton(wrapper, '从需求池添加').attributes('disabled')).toBeDefined()
  })

  it('当月不存在时默认选中最新一期月表', async () => {
    listScheduleMonths.mockResolvedValue({
      data: { data: [makeMonth(21, 2020, 1), makeMonth(22, 2020, 2)] }
    })
    const wrapper = mountPage()
    await flushPromises()

    expect(pageState(wrapper).activeMonthId).toBe(22)
    expect(getScheduleMonthView).toHaveBeenLastCalledWith(22)
  })

  it('切换月份后加载对应月表视图', async () => {
    listScheduleMonths.mockResolvedValue({ data: { data: [makeMonth(7), makeMonth(8)] } })
    const wrapper = mountPage()
    await flushPromises()
    getScheduleMonthView.mockClear()

    const group = wrapper.findComponent({ name: 'ElRadioGroup' })
    await group.vm.$emit('update:modelValue', 8)
    await group.vm.$emit('change')
    await flushPromises()

    expect(getScheduleMonthView).toHaveBeenCalledWith(8)
  })

  it('非 PM 且非管理员：隐藏管理入口，格子不可编辑', async () => {
    const wrapper = mountPage({ isAdmin: false, pmProject: false })
    await flushPromises()

    const texts = buttonTexts(wrapper)
    expect(texts).not.toContain('生成下月排期表')
    expect(texts).not.toContain('从需求池添加')
    expect(texts).not.toContain('日历管理')

    const state = pageState(wrapper)
    expect(state.canManage).toBe(false)
    expect(state.cellEditable(state.flatRows[0])).toBe(false)
  })

  it('工期变更触发行保存：提交整行格子与乐观锁版本', async () => {
    const wrapper = mountPage()
    await flushPromises()
    const state = pageState(wrapper)
    const flat = state.flatRows[0]

    state.handleEffortChange(flat)
    await flushPromises()

    expect(saveScheduleRow).toHaveBeenCalledWith(11, {
      plannedEffort: 2,
      cells: { [DAY]: 1 },
      updatedAt: 100
    })
    expect(flat.row.updatedAt).toBe(200)
  })

  it('工期低于已排合计时提示并回退（重新拉取视图，不发保存）', async () => {
    const wrapper = mountPage()
    await flushPromises()
    const state = pageState(wrapper)
    const flat = state.flatRows[0]
    flat.row.plannedEffort = 0.5

    state.handleEffortChange(flat)
    await flushPromises()

    expect(ElMessage.warning).toHaveBeenCalledWith(expect.stringContaining('超过计划工期'))
    expect(saveScheduleRow).not.toHaveBeenCalled()
    expect(getScheduleMonthView).toHaveBeenCalledTimes(2)
  })

  it('保存返回顺延信息时提示，并静默刷新月表列表', async () => {
    saveScheduleRow.mockResolvedValue({
      data: { data: { updatedAt: 200, overflow: { year: 2026, month: 12, carriedEffort: 0.5 } } }
    })
    const wrapper = mountPage()
    await flushPromises()
    const state = pageState(wrapper)
    state.flatRows[0].row.plannedEffort = 3

    state.handleEffortChange(state.flatRows[0])
    await flushPromises()

    expect(ElMessage.success).toHaveBeenCalledWith('剩余 0.5 工期已顺延至 2026年12月排期表')
    expect(listScheduleMonths).toHaveBeenCalledTimes(2)
  })

  it('保存遇 409：整表刷新恢复最新数据', async () => {
    saveScheduleRow.mockRejectedValue(new Error('数据已被他人修改，请刷新后重试'))
    const wrapper = mountPage()
    await flushPromises()
    const state = pageState(wrapper)
    state.flatRows[0].row.plannedEffort = 3

    state.handleEffortChange(state.flatRows[0])
    await flushPromises()

    expect(getScheduleMonthView).toHaveBeenCalledTimes(2)
  })

  it('整个需求添加到下月：成功后切到新月份视图', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    listScheduleMonths.mockResolvedValue({ data: { data: [makeMonth(7), makeMonth(8)] } })
    const wrapper = mountPage()
    await flushPromises()
    const state = pageState(wrapper)

    await state.handleCopy(state.flatRows[0])
    await flushPromises()

    expect(copyRequirementToNextMonth).toHaveBeenCalledWith(11)
    expect(ElMessage.success).toHaveBeenCalledWith('已添加到下月排期表（新插入 1 行）')
    expect(state.activeMonthId).toBe(8)
    expect(getScheduleMonthView).toHaveBeenLastCalledWith(8)
  })

  it('从当月移除需求后刷新当前月视图', async () => {
    vi.spyOn(ElMessageBox, 'confirm').mockResolvedValue('confirm' as never)
    const wrapper = mountPage()
    await flushPromises()
    const state = pageState(wrapper)

    await state.handleRemove(state.flatRows[0])
    await flushPromises()

    expect(removeRequirementFromMonth).toHaveBeenCalledWith(7, 5)
    expect(ElMessage.success).toHaveBeenCalledWith('已从当月排期表移除（删除 1 行）')
    expect(getScheduleMonthView).toHaveBeenLastCalledWith(7)
    expect(getScheduleMonthView).toHaveBeenCalledTimes(2)
  })

  it('生成下月排期表后选中新表并加载视图', async () => {
    const nextMonth = MONTH === 12 ? 1 : MONTH + 1
    const nextYear = MONTH === 12 ? YEAR + 1 : YEAR
    const newMonth = makeMonth(8, nextYear, nextMonth)
    createScheduleMonth.mockResolvedValue({ data: { data: newMonth } })
    listScheduleMonths.mockResolvedValue({ data: { data: [makeMonth(7), newMonth] } })
    const wrapper = mountPage()
    await flushPromises()
    const state = pageState(wrapper)

    await state.handleCreateNextMonth()
    await flushPromises()

    expect(createScheduleMonth).toHaveBeenCalledWith({ year: nextYear, month: nextMonth })
    expect(ElMessage.success).toHaveBeenCalledWith(`${nextYear}年${nextMonth}月排期表已生成`)
    expect(state.activeMonthId).toBe(8)
    expect(getScheduleMonthView).toHaveBeenLastCalledWith(8)
  })

  it('从需求池添加成功回调后刷新视图', async () => {
    const wrapper = mountPage()
    await flushPromises()
    getScheduleMonthView.mockClear()

    pageState(wrapper).handleAdded()
    await flushPromises()

    expect(getScheduleMonthView).toHaveBeenCalledWith(MONTH_ID)
  })

  it('离开拦截：有未完成需求时阻止跳转，允许进入该需求详情', async () => {
    listIncompleteRequirements.mockResolvedValue({
      data: { data: [{ requirementId: 5, reqNo: 'REQ-ACE-0005', title: '测试需求' }] }
    })
    vi.spyOn(ElMessageBox, 'alert').mockResolvedValue('confirm' as never)
    mountPage()
    await flushPromises()

    const handler = routeLeaveHandlers[0]
    expect(await handler({ path: '/reports' })).toBe(false)
    expect(ElMessageBox.alert).toHaveBeenCalled()

    listIncompleteRequirements.mockResolvedValue({
      data: { data: [{ requirementId: 5, reqNo: 'REQ-ACE-0005', title: '测试需求' }] }
    })
    expect(await handler({ path: '/requirements/5' })).toBe(true)

    listIncompleteRequirements.mockResolvedValue({ data: { data: [] } })
    expect(await handler({ path: '/reports' })).toBe(true)
  })

  it('未登录（无 token）时直接放行，不再查询未完成排期', async () => {
    const wrapper = mountPage()
    await flushPromises()
    localStorage.clear()
    listIncompleteRequirements.mockClear()

    expect(await routeLeaveHandlers[0]({ path: '/reports' })).toBe(true)
    expect(listIncompleteRequirements).not.toHaveBeenCalled()
    expect(wrapper.exists()).toBe(true)
  })
})
