import request, { type ApiResponse } from './request'

// 工作台汇总统计
export interface DashboardSummary {
  // 我的未读通知数
  unreadNotifications: number
  // 我参与的非终态需求数
  myRequirementCount: number
  // 本自然月我的排期工时合计
  monthEffort: number
  // 我的待处理 bug 数（未归档且处理状态非 DONE/SKIP）
  openBugCount: number
}

// 我参与的需求列表项（轻量）
export interface DashboardRequirement {
  id: number
  reqNo: string
  title: string
  projectId: number
  projectName: string
  // PENDING/DEV/TEST/ARCHIVING
  status: string
  priority: number
}

// 汇总卡片数据
export function getDashboardSummary() {
  return request.get<ApiResponse<DashboardSummary>>('/dashboard/summary')
}

// 我参与的需求（非终态，更新时间倒序，上限 20 条）
export function listMyRequirements() {
  return request.get<ApiResponse<DashboardRequirement[]>>('/dashboard/my-requirements')
}
