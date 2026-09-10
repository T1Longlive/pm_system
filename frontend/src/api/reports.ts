import request, { type ApiResponse } from './request'

// 需求状态分布项：项目 × 状态
export interface ReportRequirementStatus {
  projectId: number
  projectName: string
  // PENDING/DEV/TEST/ARCHIVING/ARCHIVED/CANCELLED
  status: string
  count: number
}

// 人员负载项：某人某自然月工时合计
export interface ReportWorkload {
  userId: number
  realName: string
  year: number
  month: number
  effort: number
}

// 项目概览项
export interface ReportProjectOverview {
  projectId: number
  projectName: string
  // 成员数（按人去重）
  memberCount: number
  // 非终态需求数
  requirementCount: number
  // 未归档 bug 数
  openBugCount: number
}

// 需求状态分布（全部项目 × 全部状态）
export function getRequirementStatus() {
  return request.get<ApiResponse<ReportRequirementStatus[]>>('/reports/requirement-status')
}

// 人员负载（最近 months 个自然月，含当月）
export function getWorkload(months: number) {
  return request.get<ApiResponse<ReportWorkload[]>>('/reports/workload', { params: { months } })
}

// 项目概览
export function getProjectOverview() {
  return request.get<ApiResponse<ReportProjectOverview[]>>('/reports/project-overview')
}
