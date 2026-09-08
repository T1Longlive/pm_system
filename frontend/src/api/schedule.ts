import request, { type ApiResponse } from './request'
import type { CalendarDayData } from './calendar'

// 排期月表
export interface ScheduleMonth {
  id: number
  year: number
  month: number
}

// 排期行状态：待开始 / 已作废（参与人被移除后标记）；已排/排期中/未排期由前端按工期与已排合计派生
export type ScheduleRowStatus = 'TODO' | 'VOID'

// 排期行（工种 × 负责人）
export interface ScheduleRowData {
  rowId: number
  craft: string
  userId: number
  realName: string
  status: ScheduleRowStatus
  // 是否由上月顺延生成：1是 0否
  isOverflow: 0 | 1
  // 工期：该行总工作量（PM 填写）
  plannedEffort: number | null
  // 乐观锁版本：毫秒时间戳，保存时原样带回
  updatedAt: number
  // 各日已排工作量：如 { "2026-09-04": 1 }
  cells: Record<string, number>
}

// 排期需求分组：一个需求占连续多行（需求级列合并展示）
export interface ScheduleGroup {
  // 需求在当月表中的展示序号
  seq: number
  requirementId: number
  reqNo: string
  title: string
  projectId: number
  projectName: string
  plannerName: string | null
  priority: 1 | 2 | 3
  rows: ScheduleRowData[]
}

// 月表视图：月信息 + 当月日历 + 需求分组
export interface ScheduleView {
  month: ScheduleMonth
  days: CalendarDayData[]
  groups: ScheduleGroup[]
}

// 跨月顺延信息（行保存返回，null 表示未顺延）
export interface ScheduleOverflow {
  year: number
  month: number
  carriedEffort: number
}

// 行保存入参（cells 整行替换，仅含 >0 的日期；updatedAt 乐观锁）
export interface SaveScheduleRowParams {
  plannedEffort?: number | null
  cells: Record<string, number>
  updatedAt: number
}

// 行保存返回：新版本号 + 顺延信息
export interface SaveScheduleRowResult {
  updatedAt: number
  overflow: ScheduleOverflow | null
}

// 复制排期行到下月返回（duplicated=false 表示下月已存在同键行，未重复插入）
export interface CopyRowResult {
  monthId: number
  duplicated: boolean
}

// 查询排期月表列表（升序）
export function listScheduleMonths() {
  return request.get<ApiResponse<ScheduleMonth[]>>('/schedule/months')
}

// 生成排期月表（PM/管理员；已存在时后端返回 400），返回新建的月表
export function createScheduleMonth(data: { year: number; month: number }) {
  return request.post<ApiResponse<ScheduleMonth>>('/schedule/months', data)
}

// 查询月表视图（日历 + 全部需求分组）
export function getScheduleMonthView(monthId: number) {
  return request.get<ApiResponse<ScheduleView>>(`/schedule/months/${monthId}/view`)
}

// 从需求池添加需求到月表：按参与人 × 工种生成排期行，返回生成行数
export function addRequirementToMonth(monthId: number, data: { requirementId: number }) {
  return request.post<ApiResponse<number>>(`/schedule/months/${monthId}/requirements`, data)
}

// 保存排期行（格子 + 工期；乐观锁冲突时后端返回 409）
export function saveScheduleRow(rowId: number, data: SaveScheduleRowParams) {
  return request.put<ApiResponse<SaveScheduleRowResult>>(`/schedule/rows/${rowId}`, data)
}

// 复制排期行到下月（PM 可用；下月表不存在自动创建；幂等，duplicated=false 表示该行已在下月）
export function copyScheduleRowToNextMonth(rowId: number) {
  return request.post<ApiResponse<CopyRowResult>>(`/schedule/rows/${rowId}/to-next-month`)
}
