import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import { mount } from '@vue/test-utils'
import ProgressSteps from './ProgressSteps.vue'

/**
 * ProgressSteps 迷你进度条（size=mini）的状态派生测试。
 * 阶段状态全部由「需求状态 + 工种排期日期 + 今天」推导，这里固定系统时间到 2026-11-17，
 * 用 mini 模式断言圆点/连线的 done/current 语义（不涉及 element-plus 与 vxe-table 渲染）。
 */

const TODAY = new Date(2026, 10, 17, 12)

function mountMini(status: string, extra: Record<string, unknown> = {}) {
  return mount(ProgressSteps, {
    props: { status, size: 'mini', ...extra },
    // mini 分支不渲染这些组件，但模板解析仍会解析标签：统一 stub 掉，避免测试日志噪声
    global: {
      stubs: {
        'el-avatar': true,
        'el-tooltip': true,
        'el-icon': true,
        Check: true,
        UserFilled: true,
        CircleCloseFilled: true
      }
    }
  })
}

const dotsOf = (wrapper: ReturnType<typeof mountMini>) => wrapper.findAll('.mini-dot')
const linesOf = (wrapper: ReturnType<typeof mountMini>) => wrapper.findAll('.mini-line')

beforeEach(() => {
  vi.useFakeTimers()
  vi.setSystemTime(TODAY)
})

afterEach(() => {
  vi.useRealTimers()
})

describe('ProgressSteps 迷你进度条', () => {
  it('待排期：需求建立完成、排期进行中，连线按两端状态取高者（绿）', () => {
    const wrapper = mountMini('PENDING')
    const dots = dotsOf(wrapper)
    const lines = linesOf(wrapper)

    expect(dots).toHaveLength(6)
    expect(lines).toHaveLength(5)
    expect(dots[0].classes()).toContain('done')
    expect(dots[1].classes()).toContain('current')
    expect(dots[2].classes()).not.toContain('done')
    expect(lines[0].classes()).toContain('done')
    expect(lines[1].classes()).not.toContain('done')
    expect(lines[1].classes()).not.toContain('current')
  })

  it('已排期：建立与排期两个节点完成，开发未开始时连线断开', () => {
    const wrapper = mountMini('READY')
    const dots = dotsOf(wrapper)
    const lines = linesOf(wrapper)

    expect(dots[0].classes()).toContain('done')
    expect(dots[1].classes()).toContain('done')
    expect(dots[2].classes()).not.toContain('current')
    expect(lines[0].classes()).toContain('done')
    expect(lines[1].classes()).not.toContain('done')
  })

  it('开发中：开发工种排期覆盖今天时开发节点高亮', () => {
    const wrapper = mountMini('DEV', {
      craftSchedules: [{ craft: 'FE', startDate: '2026-11-10', endDate: '2026-11-20' }]
    })

    expect(dotsOf(wrapper)[2].classes()).toContain('current')
  })

  it('开发中：工种排期尚未开始则开发节点保持未开始', () => {
    const wrapper = mountMini('DEV', {
      craftSchedules: [{ craft: 'FE', startDate: '2026-11-20', endDate: '2026-11-25' }]
    })

    expect(dotsOf(wrapper)[2].classes()).not.toContain('current')
    expect(dotsOf(wrapper)[2].classes()).not.toContain('done')
  })

  it('测试中：QA 排期已结束则测试节点完成、归档节点仍未开始', () => {
    const wrapper = mountMini('TEST', {
      craftSchedules: [{ craft: 'QA', startDate: '2026-11-01', endDate: '2026-11-15' }]
    })

    expect(dotsOf(wrapper)[3].classes()).toContain('done')
    expect(dotsOf(wrapper)[4].classes()).not.toContain('current')
  })

  it('待归档：归档节点高亮为进行中', () => {
    const wrapper = mountMini('ARCHIVING')

    expect(dotsOf(wrapper)[3].classes()).toContain('done')
    expect(dotsOf(wrapper)[4].classes()).toContain('current')
    expect(dotsOf(wrapper)[5].classes()).not.toContain('done')
  })

  it('已归档：六个节点与五段连线全部完成', () => {
    const wrapper = mountMini('ARCHIVED')

    dotsOf(wrapper).forEach((dot) => expect(dot.classes()).toContain('done'))
    linesOf(wrapper).forEach((line) => expect(line.classes()).toContain('done'))
  })

  it('已取消：全部置灰且尾部显示取消标记', () => {
    const wrapper = mountMini('CANCELLED')

    dotsOf(wrapper).forEach((dot) => {
      expect(dot.classes()).not.toContain('done')
      expect(dot.classes()).not.toContain('current')
    })
    expect(wrapper.find('.mini-cancel').text()).toBe('已取消')
  })
})
