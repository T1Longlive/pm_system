import { beforeEach, describe, expect, it, vi } from 'vitest'
import { flushPromises, mount } from '@vue/test-utils'
import ElementPlus, { ElMessage } from 'element-plus'

/**
 * 统计报表页单测：三接口并行加载、KPI 派生、月份切换与卸载清理。
 * echarts 用最小桩件替代，jsdom 无 canvas，不渲染真实图表。
 */

const chartMock = vi.hoisted(() => {
  const charts: Array<{ setOption: ReturnType<typeof vi.fn>; resize: ReturnType<typeof vi.fn>; dispose: ReturnType<typeof vi.fn> }> = []
  return {
    charts,
    use: vi.fn(),
    init: vi.fn(() => {
      const chart = { setOption: vi.fn(), resize: vi.fn(), dispose: vi.fn() }
      charts.push(chart)
      return chart
    })
  }
})

const getRequirementStatus = vi.hoisted(() => vi.fn())
const getWorkload = vi.hoisted(() => vi.fn())
const getProjectOverview = vi.hoisted(() => vi.fn())

vi.mock('echarts/core', () => ({ use: chartMock.use, init: chartMock.init }))
vi.mock('echarts/charts', () => ({ BarChart: {}, PieChart: {}, HeatmapChart: {} }))
vi.mock('echarts/components', () => ({
  GridComponent: {},
  LegendComponent: {},
  TooltipComponent: {},
  TitleComponent: {},
  VisualMapComponent: {},
  DataZoomComponent: {}
}))
vi.mock('echarts/renderers', () => ({ CanvasRenderer: {} }))
vi.mock('@/api/reports', () => ({
  getRequirementStatus,
  getWorkload,
  getProjectOverview
}))

import Reports from './Reports.vue'

const statusRows = [
  { projectId: 1, projectName: 'A项目', status: 'PENDING', count: 3 },
  { projectId: 1, projectName: 'A项目', status: 'DEV', count: 2 },
  { projectId: 2, projectName: 'B项目', status: 'ARCHIVED', count: 1 }
]
const workloadRows = [
  { userId: 1, realName: '张三', year: 2026, month: 11, effort: 5 },
  { userId: 2, realName: '李四', year: 2026, month: 11, effort: 8 }
]
const overviewRows = [
  { projectId: 1, projectName: 'A项目', memberCount: 3, requirementCount: 5, openBugCount: 2 },
  { projectId: 2, projectName: 'B项目', memberCount: 1, requirementCount: 1, openBugCount: 4 }
]

function mountPage() {
  return mount(Reports, { global: { plugins: [ElementPlus] } })
}

beforeEach(() => {
  vi.restoreAllMocks()
  vi.clearAllMocks()
  chartMock.charts.length = 0
  vi.spyOn(ElMessage, 'error').mockImplementation(() => undefined as never)
  getRequirementStatus.mockResolvedValue({ data: { data: statusRows } })
  getWorkload.mockResolvedValue({ data: { data: workloadRows } })
  getProjectOverview.mockResolvedValue({ data: { data: overviewRows } })
})

describe('Reports 统计报表页', () => {
  it('并行加载三类数据并计算 KPI 卡片', async () => {
    const wrapper = mountPage()
    await flushPromises()

    expect(getRequirementStatus).toHaveBeenCalledTimes(1)
    expect(getWorkload).toHaveBeenCalledWith(6)
    expect(getProjectOverview).toHaveBeenCalledTimes(1)
    expect(wrapper.findAll('.kpi-value').map((el) => el.text())).toEqual(['6', '5', '2', '6'])
    // 环形图图例展示各状态
    expect(wrapper.text()).toContain('待排期')
    expect(wrapper.text()).toContain('开发中')
    expect(wrapper.text()).toContain('已归档')
    expect(chartMock.init).toHaveBeenCalledTimes(3)
  })

  it('切换人员负载统计月份后按新月份重新请求', async () => {
    const wrapper = mountPage()
    await flushPromises()
    getWorkload.mockClear()

    const monthSelect = wrapper.findAllComponents({ name: 'ElSelect' })[0]
    await monthSelect.vm.$emit('update:modelValue', 3)
    await flushPromises()

    expect(getWorkload).toHaveBeenCalledWith(3)
  })

  it('接口失败时提示错误并展示空状态', async () => {
    getRequirementStatus.mockRejectedValue(new Error('boom'))
    getWorkload.mockRejectedValue(new Error('boom'))
    getProjectOverview.mockRejectedValue(new Error('boom'))
    const wrapper = mountPage()
    await flushPromises()

    expect(ElMessage.error).toHaveBeenCalledWith('需求状态分布加载失败')
    expect(ElMessage.error).toHaveBeenCalledWith('人员负载加载失败')
    expect(ElMessage.error).toHaveBeenCalledWith('项目概览加载失败')
    expect(wrapper.text()).toContain('暂无需求数据')
    expect(wrapper.text()).toContain('区间内暂无排期工时数据')
    expect(wrapper.text()).toContain('暂无项目数据')
  })

  it('卸载时释放全部图表实例', async () => {
    const wrapper = mountPage()
    await flushPromises()
    wrapper.unmount()

    expect(chartMock.charts).toHaveLength(3)
    chartMock.charts.forEach((chart) => expect(chart.dispose).toHaveBeenCalled())
  })
})
