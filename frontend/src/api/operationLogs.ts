import request, { type ApiResponse } from './request'
import type { PageResult } from './user'

// 操作日志项
export interface OperationLogItem {
  id: number
  // 业务类型：REQUIREMENT/BUG/PROJECT
  bizType: string
  bizId: number
  // 业务编号（需求 REQ-xxx / Bug BUG-xxx / 项目缩写；业务行被删后可能为空）
  bizNo: string | null
  bizName: string | null
  projectId: number
  projectName: string | null
  // 动作码（前端映射中文与颜色）
  action: string
  operatorId: number
  operatorName: string | null
  remark: string
  createdAt: string
}

// 操作人下拉选项
export interface OperatorOption {
  id: number
  realName: string
  username: string
}

// 日志查询参数（空值不传）
export interface OperationLogQuery {
  bizType?: string
  action?: string
  operatorId?: number
  projectId?: number
  keyword?: string
  startDate?: string
  endDate?: string
  page: number
  size: number
}

// 分页查询操作日志（管理员全量 / 项目经理限自己创建的项目）
export function pageOperationLogs(params: OperationLogQuery) {
  return request.get<ApiResponse<PageResult<OperationLogItem>>>('/operation-logs', { params })
}

// 操作人下拉（可见范围内日志出现过的操作人去重）
export function listLogOperators() {
  return request.get<ApiResponse<OperatorOption[]>>('/operation-logs/operators')
}
