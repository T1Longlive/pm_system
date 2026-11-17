import { describe, expect, it } from 'vitest'
import {
  buildFilterOptions,
  buildMergeCells,
  buildRowCells,
  computeScheduledSum,
  deriveRowStatus,
  filterKeyOf,
  filterScheduleRows,
  isConflictError,
  localCellSum,
  type FilterableScheduleRow
} from './schedule'

// ==================== 构造工具 ====================

function row(
  requirementId: number,
  overrides: Partial<{
    title: string
    projectName: string
    plannerName: string | null
    priority: number
    realName: string | null
  }> = {}
): FilterableScheduleRow {
  return {
    requirementId,
    title: overrides.title ?? '需求A',
    projectName: overrides.projectName ?? '项目A',
    plannerName: overrides.plannerName ?? '策划A',
    priority: overrides.priority ?? 2,
    row: { realName: overrides.realName ?? '张三' }
  }
}

// ==================== 筛选 ====================

describe('filterKeyOf', () => {
  it('优先级归一化为字符串', () => {
    expect(filterKeyOf(row(1, { priority: 1 }), 'priority')).toBe('1')
  })

  it('负责人取行级字段', () => {
    expect(filterKeyOf(row(1, { realName: '李四' }), 'realName')).toBe('李四')
  })

  it('空值统一为空串', () => {
    expect(filterKeyOf(row(1, { title: '' }), 'title')).toBe('')
  })
})

describe('filterScheduleRows', () => {
  const rows = [
    row(1, { title: '需求A', priority: 1, realName: '张三' }),
    row(1, { title: '需求A', priority: 1, realName: '李四' }),
    row(2, { title: '需求B', priority: 2, realName: '张三' })
  ]
  const empty = { title: null, projectName: null, plannerName: null, priority: null, realName: null }

  it('无筛选时原样返回', () => {
    expect(filterScheduleRows(rows, empty)).toHaveLength(3)
  })

  it('需求级条件过滤整行集合', () => {
    const result = filterScheduleRows(rows, { ...empty, priority: ['1'] })
    expect(result.map((r) => r.requirementId)).toEqual([1, 1])
  })

  it('负责人为行级条件，只保留命中行', () => {
    const result = filterScheduleRows(rows, { ...empty, realName: ['张三'] })
    expect(result).toHaveLength(2)
    expect(result.map((r) => r.title)).toEqual(['需求A', '需求B'])
  })

  it('多个条件按 AND 叠加', () => {
    const result = filterScheduleRows(rows, { ...empty, priority: ['1'], realName: ['李四'] })
    expect(result).toHaveLength(1)
    expect(result[0].row.realName).toBe('李四')
  })
})

describe('buildFilterOptions', () => {
  const rows = [
    row(1, { projectName: '项目A', title: '需求A', priority: 1, realName: '张三' }),
    row(1, { projectName: '项目A', title: '需求A', priority: 1, realName: '李四' }),
    row(2, { projectName: '项目A', title: '需求B', priority: 2, realName: '张三' }),
    row(3, { projectName: '项目B', title: '', priority: 3, realName: '张三' })
  ]

  it('需求级列按需求去重计数（合并单元格只算一次）', () => {
    const options = buildFilterOptions(rows, 'projectName')
    expect(options).toEqual([
      { value: '项目A', label: '项目A', count: 2 },
      { value: '项目B', label: '项目B', count: 1 }
    ])
  })

  it('负责人按行计数', () => {
    const options = buildFilterOptions(rows, 'realName')
    expect(options).toEqual([
      { value: '张三', label: '张三', count: 3 },
      { value: '李四', label: '李四', count: 1 }
    ])
  })

  it('优先级按数值升序并转中文标签', () => {
    const options = buildFilterOptions(rows, 'priority')
    expect(options.map((o) => o.value)).toEqual(['1', '2', '3'])
    expect(options.map((o) => o.label)).toEqual(['高', '中', '低'])
  })

  it('空值显示为「（空）」', () => {
    const options = buildFilterOptions(rows, 'title')
    expect(options.find((o) => o.value === '')?.label).toBe('（空）')
  })
})

// ==================== 合并单元格 ====================

describe('buildMergeCells', () => {
  const cols = [0, 1]

  it('连续同需求段生成纵向合并', () => {
    const rows = [
      { requirementId: 1 },
      { requirementId: 1 },
      { requirementId: 2 },
      { requirementId: 3 },
      { requirementId: 3 }
    ]
    expect(buildMergeCells(rows, cols)).toEqual([
      { row: 0, col: 0, rowspan: 2, colspan: 1 },
      { row: 0, col: 1, rowspan: 2, colspan: 1 },
      { row: 3, col: 0, rowspan: 2, colspan: 1 },
      { row: 3, col: 1, rowspan: 2, colspan: 1 }
    ])
  })

  it('单行需求不生成合并', () => {
    const rows = [{ requirementId: 1 }, { requirementId: 2 }]
    expect(buildMergeCells(rows, cols)).toEqual([])
  })

  it('空行集返回空数组', () => {
    expect(buildMergeCells([], cols)).toEqual([])
  })
})

// ==================== 已排合计 ====================

describe('localCellSum / computeScheduledSum', () => {
  it('本地求和消除浮点尾差', () => {
    expect(localCellSum({ a: 0.1, b: 0.2 })).toBe(0.3)
    expect(localCellSum({})).toBe(0)
  })

  it('未记录加载值时退化为本地求和', () => {
    expect(computeScheduledSum(5, undefined, { a: 0.5 })).toBe(0.5)
  })

  it('用跨月组合计减去加载值再加本地值', () => {
    expect(computeScheduledSum(5, 2, { a: 1 })).toBe(4)
  })

  it('结果保留一位小数', () => {
    expect(computeScheduledSum(0.3, 0.1, { a: 0.2 })).toBe(0.4)
  })
})

describe('buildRowCells', () => {
  it('只保留本月大于 0 的格子', () => {
    const result = buildRowCells(
      { d1: 1, d2: 0, d3: 0.5, d4: 1 },
      ['d1', 'd2', 'd3']
    )
    expect(result).toEqual({ d1: 1, d3: 0.5 })
  })
})

// ==================== 行状态 ====================

describe('deriveRowStatus', () => {
  it('VOID 优先于需求归档', () => {
    expect(deriveRowStatus({
      rowStatus: 'VOID', reqStatus: 'ARCHIVED', plannedEffort: 3, scheduledSum: 3
    })).toEqual({ label: '已作废', type: 'info' })
  })

  it('需求归档时只读', () => {
    expect(deriveRowStatus({
      rowStatus: 'TODO', reqStatus: 'ARCHIVED', plannedEffort: 3, scheduledSum: 0
    })).toEqual({ label: '已归档', type: 'info' })
  })

  it('未设工期为待排期', () => {
    expect(deriveRowStatus({
      rowStatus: 'TODO', reqStatus: 'PENDING', plannedEffort: null, scheduledSum: 0
    })).toEqual({ label: '待排期', type: 'info' })
  })

  it('工期为 0 也是待排期', () => {
    expect(deriveRowStatus({
      rowStatus: 'TODO', reqStatus: 'PENDING', plannedEffort: 0, scheduledSum: 0
    })).toEqual({ label: '待排期', type: 'info' })
  })

  it('已排合计等于工期为已排期', () => {
    expect(deriveRowStatus({
      rowStatus: 'TODO', reqStatus: 'DEV', plannedEffort: 2, scheduledSum: 2
    })).toEqual({ label: '已排期', type: 'success' })
  })

  it('已排合计小于工期为排期中', () => {
    expect(deriveRowStatus({
      rowStatus: 'TODO', reqStatus: 'DEV', plannedEffort: 2, scheduledSum: 1
    })).toEqual({ label: '排期中', type: 'warning' })
  })
})

// ==================== 冲突识别 ====================

describe('isConflictError', () => {
  it('识别 HTTP 409', () => {
    expect(isConflictError({ response: { status: 409 } })).toBe(true)
  })

  it('识别业务码 409', () => {
    expect(isConflictError({ response: { data: { code: 409 } } })).toBe(true)
  })

  it('识别拦截器转译的冲突文案', () => {
    expect(isConflictError({ message: '数据已被他人修改，请刷新后重试' })).toBe(true)
  })

  it('非冲突错误返回 false', () => {
    expect(isConflictError({ message: '网络错误' })).toBe(false)
    expect(isConflictError(null)).toBe(false)
    expect(isConflictError('409')).toBe(false)
  })
})
