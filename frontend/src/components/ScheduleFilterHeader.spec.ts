import { describe, expect, it } from 'vitest'
import { shallowMount } from '@vue/test-utils'
import ScheduleFilterHeader from './ScheduleFilterHeader.vue'

describe('ScheduleFilterHeader', () => {
  it('渲染标题', () => {
    const wrapper = shallowMount(ScheduleFilterHeader, {
      props: { title: '需求名称', active: false },
      global: { stubs: { 'el-icon': true } }
    })
    expect(wrapper.find('.th-title').text()).toBe('需求名称')
  })

  it('active 控制筛选按钮高亮类', () => {
    const wrapper = shallowMount(ScheduleFilterHeader, {
      props: { title: '项目', active: true },
      global: { stubs: { 'el-icon': true } }
    })
    expect(wrapper.find('.filter-trigger').classes()).toContain('active')
  })

  it('点击三角按钮向父组件抛出 toggle 事件与原生事件', async () => {
    const wrapper = shallowMount(ScheduleFilterHeader, {
      props: { title: '负责人', active: false },
      global: { stubs: { 'el-icon': true } }
    })

    await wrapper.find('.filter-trigger').trigger('click')

    const events = wrapper.emitted('toggle')
    expect(events).toHaveLength(1)
    expect(events?.[0]?.[0]).toBeInstanceOf(MouseEvent)
  })
})
