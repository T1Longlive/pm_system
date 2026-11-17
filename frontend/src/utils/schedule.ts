import { priorityLabel } from '@/constants/requirement'

/**
 * 排期页纯逻辑：从 Schedule.vue 抽离，便于 Vitest 直接覆盖。
 * 这些函数不依赖 Vue 响应式、不发起请求，只做数据变换。
 */

export type ScheduleFilterField = 'title' | 'projectName' | 'plannerName' | 'priority' | 'realName'

export const SCHEDULE_FILTER_FIELDS: ScheduleFilterField[] = [
  'title',
  'projectName',
  'plannerName',
  'priority',
  'realName'
]

export type ScheduleFilterState = Record<ScheduleFilterField, string[] | null>

/** 参与筛选/合并计算的最小行形状（Schedule.vue 的 FlatRow 结构上兼容） */
export interface FilterableScheduleRow {
  requirementId: number
  title: string
  projectName: string
  plannerName: string | null
  priority: number
  row: {
    realName: string | null
  }
}

export interface ScheduleFilterOption {
  value: string
  label: string
  count: number
}

export interface ScheduleMergeCell {
  row: number
  col: number
  rowspan: number
  colspan: number
}

/** 行字段取值归一化：优先级转字符串，负责人取行级字段，空值统一 '' */
export function filterKeyOf(row: FilterableScheduleRow, field: ScheduleFilterField): string {
  switch (field) {
    case 'priority':
      return String(row.priority ?? '')
    case 'realName':
      return row.row.realName ?? ''
    default:
      return (row[field] ?? '') as string
  }
}

/** 按筛选状态过滤：负责人为行级条件，其余为需求级条件（同一需求的行全部保留或全部隐藏） */
export function filterScheduleRows<T extends FilterableScheduleRow>(
  rows: T[],
  filters: ScheduleFilterState
): T[] {
  let result = rows
  for (const field of SCHEDULE_FILTER_FIELDS) {
    const values = filters[field]
    if (!values || values.length === 0) continue
    const set = new Set(values)
    result = result.filter((row) => set.has(filterKeyOf(row, field)))
  }
  return result
}

/**
 * 筛选面板候选项：从当前月全部行（未筛选）去重统计。
 * 需求级列按需求去重计数（合并单元格只算一次）；负责人列按行计数。
 */
export function buildFilterOptions(
  rows: FilterableScheduleRow[],
  field: ScheduleFilterField
): ScheduleFilterOption[] {
  const counter = new Map<string, number>()
  if (field === 'realName') {
    for (const row of rows) {
      const value = filterKeyOf(row, field)
      counter.set(value, (counter.get(value) || 0) + 1)
    }
  } else {
    const countedByRequirement = new Map<number, Set<string>>()
    for (const row of rows) {
      const value = filterKeyOf(row, field)
      let seen = countedByRequirement.get(row.requirementId)
      if (!seen) {
        seen = new Set()
        countedByRequirement.set(row.requirementId, seen)
      }
      if (!seen.has(value)) {
        seen.add(value)
        counter.set(value, (counter.get(value) || 0) + 1)
      }
    }
  }

  const labelOf = (value: string) => {
    if (value === '') return '（空）'
    if (field === 'priority') return priorityLabel(Number(value) as 1 | 2 | 3)
    return value
  }
  const options = [...counter.entries()].map(([value, count]) => ({
    value,
    label: labelOf(value),
    count
  }))
  if (field === 'priority') {
    options.sort((a, b) => Number(a.value) - Number(b.value))
  }
  return options
}

/**
 * 合并单元格：筛选后同需求幸存行仍连续，按连续段计算需求级列纵向合并。
 * 返回 vxe-table 的 mergeCells 结构。
 */
export function buildMergeCells(
  rows: { requirementId: number }[],
  groupColIndexes: number[]
): ScheduleMergeCell[] {
  const result: ScheduleMergeCell[] = []
  let index = 0
  let i = 0
  while (i < rows.length) {
    const requirementId = rows[i].requirementId
    let j = i
    while (j < rows.length && rows[j].requirementId === requirementId) j++
    const span = j - i
    if (span > 1) {
      for (const col of groupColIndexes) {
        result.push({ row: index, col, rowspan: span, colspan: 1 })
      }
    }
    index += span
    i = j
  }
  return result
}

/** 本地格子合计（保留一位小数，消除浮点尾差） */
export function localCellSum(cells: Record<string, number>): number {
  let sum = 0
  for (const value of Object.values(cells)) sum += value || 0
  return Math.round(sum * 10) / 10
}

/**
 * 已排合计（跨全部月份）：
 * 后端组内合计 - 加载时本行格子合计 + 本地当前格子合计。
 * sumAtLoad 为 undefined 时（新加载尚未记录）退化为本地求和。
 */
export function computeScheduledSum(
  groupScheduledSum: number,
  sumAtLoad: number | undefined,
  cells: Record<string, number>
): number {
  if (sumAtLoad === undefined) return localCellSum(cells)
  return Math.round((groupScheduledSum - sumAtLoad + localCellSum(cells)) * 10) / 10
}

/** 组装整行 cells：仅保留 >0 的日期（PUT 整行替换约定） */
export function buildRowCells(
  cells: Record<string, number>,
  days: string[]
): Record<string, number> {
  const result: Record<string, number> = {}
  for (const day of days) {
    const value = cells[day]
    if (value != null && value > 0) result[day] = value
  }
  return result
}

export type ScheduleRowStatusType = 'primary' | 'success' | 'info' | 'warning'

/**
 * 行状态三态派生（优先级：作废 > 需求归档 > 未设工期 > 已排期 > 排期中）：
 * VOID 已作废；需求已归档 → 已归档（只读）；未设工期 → 待排期；
 * 跨月已排合计 ≥ 工期 → 已排期；否则排期中。
 */
export function deriveRowStatus(input: {
  rowStatus: string
  reqStatus: string
  plannedEffort: number | null
  scheduledSum: number
}): { label: string; type: ScheduleRowStatusType } {
  if (input.rowStatus === 'VOID') return { label: '已作废', type: 'info' }
  if (input.reqStatus === 'ARCHIVED') return { label: '已归档', type: 'info' }
  if (!input.plannedEffort || input.plannedEffort <= 0) return { label: '待排期', type: 'info' }
  if (input.scheduledSum >= input.plannedEffort) return { label: '已排期', type: 'success' }
  return { label: '排期中', type: 'warning' }
}

/** 是否乐观锁冲突：HTTP 409 / body code=409 / 拦截器转译的冲突文案 */
export function isConflictError(error: unknown): boolean {
  if (typeof error !== 'object' || error === null) return false
  const e = error as {
    response?: { status?: number; data?: { code?: number } }
    message?: string
  }
  if (e.response?.status === 409 || e.response?.data?.code === 409) return true
  return !!e.message && e.message.includes('已被')
}
