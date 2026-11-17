import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus from 'element-plus'

/**
 * 需求池选择弹窗单测：打开加载、已在当月禁选、单选切换、提交成功/失败。
 */

const listRequirements = vi.hoisted(() => vi.fn())
const addRequirementToMonth = vi.hoisted(() => vi.fn())

vi.mock('@/api/requirement', () => ({ listRequirements }))
vi.mock('@/api/schedule', () => ({ addRequirementToMonth }))

import ScheduleRequirementPicker from './ScheduleRequirementPicker.vue'

const rows = [
  {
    id: 1, reqNo: 'REQ-ACE-0001', title: '已在当月的需求', projectId: 1, projectName: '示例项目',
    priority: 2, status: 'PENDING', plannerId: 9, plannerName: '策划',
    participants: [{ userId: 9, realName: '策划', craft: 'PLANNER' }]
  },
  {
    id: 2, reqNo: 'REQ-ACE-0002', title: '可添加的需求', projectId: 1, projectName: '示例项目',
    priority: 1, status: 'PENDING', plannerId: 9, plannerName: '策划',
    participants: [{ userId: 9, realName: '策划', craft: 'PLANNER' }]
  }
]

function mountPicker(existingIds: number[] = [1]) {
  return mount(ScheduleRequirementPicker, {
    props: { modelValue: false, monthId: 7, existingIds },
    global: { plugins: [ElementPlus], stubs: { teleport: true } }
  })
}

const confirmButton = (wrapper: ReturnType<typeof mountPicker>) =>
  wrapper.findAllComponents({ name: 'ElButton' }).find((button) => button.text() === '确定')!

beforeEach(() => {
  vi.clearAllMocks()
  listRequirements.mockResolvedValue({ data: { data: { records: rows, total: rows.length } } })
  addRequirementToMonth.mockResolvedValue({ data: { data: 2 } })
})

describe('ScheduleRequirementPicker 需求池弹窗', () => {
  it('打开弹窗时拉取未终态需求池', async () => {
    const wrapper = mountPicker()
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    expect(listRequirements).toHaveBeenCalledWith({ excludeTerminal: true, page: 1, size: 500 })
  })

  it('已在当月的需求禁选：点击后仍不可提交', async () => {
    const wrapper = mountPicker([1])
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    const table = wrapper.findComponent({ name: 'ElTable' })
    table.vm.$emit('row-click', rows[0])
    await flushPromises()

    expect(confirmButton(wrapper).attributes('disabled')).toBeDefined()
  })

  it('选择可添加需求后提交：带上 monthId/requirementId，成功后关闭并通知父组件', async () => {
    const wrapper = mountPicker([1])
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    wrapper.findComponent({ name: 'ElTable' }).vm.$emit('row-click', rows[1])
    await flushPromises()
    await confirmButton(wrapper).trigger('click')
    await flushPromises()

    expect(addRequirementToMonth).toHaveBeenCalledWith(7, { requirementId: 2 })
    expect(wrapper.emitted('success')).toHaveLength(1)
    const updates = wrapper.emitted('update:modelValue')!
    expect(updates[updates.length - 1]).toEqual([false])
  })

  it('再次点击同一行取消选择', async () => {
    const wrapper = mountPicker([1])
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    const table = wrapper.findComponent({ name: 'ElTable' })
    table.vm.$emit('row-click', rows[1])
    await flushPromises()
    expect(confirmButton(wrapper).attributes('disabled')).toBeUndefined()

    table.vm.$emit('row-click', rows[1])
    await flushPromises()
    expect(confirmButton(wrapper).attributes('disabled')).toBeDefined()
  })

  it('提交失败时保持弹窗打开，不发出 success', async () => {
    addRequirementToMonth.mockRejectedValue(new Error('boom'))
    const wrapper = mountPicker([1])
    await wrapper.setProps({ modelValue: true })
    await flushPromises()

    wrapper.findComponent({ name: 'ElTable' }).vm.$emit('row-click', rows[1])
    await flushPromises()
    await confirmButton(wrapper).trigger('click')
    await flushPromises()

    expect(wrapper.emitted('success')).toBeUndefined()
    expect(wrapper.emitted('update:modelValue')?.some(([value]) => value === false)).toBeFalsy()
  })
})
