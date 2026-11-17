import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'
import type { CalendarDayData } from '@/api/calendar'

/**
 * 日历管理页单测：默认当月加载、月份切换、单日编辑回填与保存。
 */

const getCalendar = vi.hoisted(() => vi.fn())
const updateCalendarDay = vi.hoisted(() => vi.fn())

vi.mock('@/api/calendar', () => ({ getCalendar, updateCalendarDay }))

import AdminCalendar from './AdminCalendar.vue'

const now = new Date()
const YEAR = now.getFullYear()
const MONTH = now.getMonth() + 1

const days: CalendarDayData[] = [
  { day: `${YEAR}-${String(MONTH).padStart(2, '0')}-01`, type: 'WEEKEND', name: null },
  { day: `${YEAR}-${String(MONTH).padStart(2, '0')}-02`, type: 'WORKDAY', name: null },
  { day: `${YEAR}-${String(MONTH).padStart(2, '0')}-03`, type: 'HOLIDAY', name: '测试节' },
  { day: `${YEAR}-${String(MONTH).padStart(2, '0')}-04`, type: 'ADJUSTED', name: null }
]

interface CalendarState {
  yearMonth: string
  calendar: { days: CalendarDayData[] } | null
  editForm: { visible: boolean; day: string; type: string; name: string }
}

function mountPage() {
  return mount(AdminCalendar, {
    global: { plugins: [ElementPlus], stubs: { teleport: true } }
  })
}

const pageState = (wrapper: ReturnType<typeof mountPage>) =>
  (wrapper.vm.$ as never as { setupState: CalendarState }).setupState

const findButton = (wrapper: ReturnType<typeof mountPage>, text: string) =>
  wrapper.findAllComponents({ name: 'ElButton' }).find((item) => item.text() === text)!

beforeEach(() => {
  vi.restoreAllMocks()
  vi.clearAllMocks()
  vi.spyOn(ElMessage, 'success').mockImplementation(() => undefined as never)
  vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
  getCalendar.mockResolvedValue({ data: { data: { year: YEAR, month: MONTH, days } } })
  updateCalendarDay.mockResolvedValue({ data: { data: null } })
})

describe('AdminCalendar 日历管理页', () => {
  it('挂载即加载当前年月，并按类型渲染日期格', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(getCalendar).toHaveBeenCalledWith(YEAR, MONTH)
    expect(pageState(wrapper).yearMonth).toBe(`${YEAR}-${String(MONTH).padStart(2, '0')}`)
    expect(wrapper.findAll('.day-cell')).toHaveLength(days.length)
    expect(wrapper.find('.type-weekend').text()).toContain('休')
    expect(wrapper.find('.type-holiday').text()).toContain('测试节')
    expect(wrapper.find('.type-adjusted').text()).toContain('调休')
  })

  it('切换年月后按新月份重新请求', async () => {
    const wrapper = mountPage()
    await flushPromises()
    getCalendar.mockClear()

    const picker = wrapper.findComponent({ name: 'ElDatePicker' })
    await picker.vm.$emit('update:modelValue', '2026-01')
    await picker.vm.$emit('change')
    await flushPromises()

    expect(getCalendar).toHaveBeenCalledWith(2026, 1)
  })

  it('点击日期格打开编辑弹窗并回填当天类型与名称', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.findAll('.day-cell')[2].trigger('click')
    const state = pageState(wrapper)
    expect(state.editForm.visible).toBe(true)
    expect(state.editForm.day).toBe(days[2].day)
    expect(state.editForm.type).toBe('HOLIDAY')
    expect(state.editForm.name).toBe('测试节')
    expect(wrapper.text()).toContain(`编辑日历 · ${days[2].day}`)
  })

  it('选择节假日后填写名称并保存，成功后关闭弹窗并刷新', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.findAll('.day-cell')[1].trigger('click')
    const radioGroup = wrapper.findComponent({ name: 'ElRadioGroup' })
    await radioGroup.vm.$emit('update:modelValue', 'HOLIDAY')
    await flushPromises()

    await wrapper.find('input[placeholder="如：国庆节"]').setValue('国庆节')
    await findButton(wrapper, '保存').trigger('click')
    await flushPromises()

    expect(updateCalendarDay).toHaveBeenCalledWith(days[1].day, {
      type: 'HOLIDAY',
      name: '国庆节'
    })
    expect(ElMessage.success).toHaveBeenCalledWith(`${days[1].day} 日历已更新`)
    expect(pageState(wrapper).editForm.visible).toBe(false)
    expect(getCalendar).toHaveBeenCalledTimes(2)
  })

  it('非节假日保存时不携带名称', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.findAll('.day-cell')[1].trigger('click')
    await findButton(wrapper, '保存').trigger('click')
    await flushPromises()

    const [day, payload] = updateCalendarDay.mock.calls[0]
    expect(day).toBe(days[1].day)
    expect(payload.type).toBe('WORKDAY')
    expect(payload.name).toBeUndefined()
  })

  it('保存失败时保持弹窗打开且不提示成功', async () => {
    updateCalendarDay.mockRejectedValue(new Error('boom'))
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.findAll('.day-cell')[1].trigger('click')
    await findButton(wrapper, '保存').trigger('click')
    await flushPromises()

    expect(pageState(wrapper).editForm.visible).toBe(true)
    expect(ElMessage.success).not.toHaveBeenCalled()
  })

  it('取消编辑不调用接口', async () => {
    const wrapper = mountPage()
    await flushPromises()

    await wrapper.findAll('.day-cell')[1].trigger('click')
    await findButton(wrapper, '取消').trigger('click')
    await flushPromises()

    expect(updateCalendarDay).not.toHaveBeenCalled()
    expect(pageState(wrapper).editForm.visible).toBe(false)
  })
})
