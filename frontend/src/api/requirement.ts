import request, { type ApiResponse } from './request'
import type { PageResult } from './user'

// 需求参与人（项目成员 × 工种，同一用户可按不同工种出现多行）
export interface ReqParticipant {
  userId: number
  realName: string
  craft: string
}

// 参与人入参（提交时无需 realName）
export interface ReqParticipantInput {
  userId: number
  craft: string
}

// 需求附件（详情返回时带 id，提交时只需 url 等四项）
export interface ReqAttachment {
  id?: number
  url: string
  fileName: string
  fileType: 'IMAGE' | 'DOC'
  fileSize: number
}

// 需求列表项
export interface ReqListItem {
  id: number
  reqNo: string
  projectId: number
  projectName: string
  title: string
  // 优先级：1高 2中 3低
  priority: 1 | 2 | 3
  status: string
  plannerId: number | null
  plannerName: string | null
  // 创建人 ID
  createdBy: number
  // 创建人姓名（展示用）
  creatorName: string
  createdAt: string
  // 乐观锁版本：毫秒时间戳，编辑提交时原样带回
  updatedAt: number
  participants: ReqParticipant[]
  // 我在该需求中的角色：PM 或工种码；空数组 = 非项目成员（只读）
  myRole: string[]
}

// 需求详情 = 列表全部字段 + 富文本描述 + 附件 + 工种排期区间
export interface ReqDetail extends ReqListItem {
  description: string
  attachments: ReqAttachment[]
  // 各工种排期日期区间（全部非 VOID 排期行按工种聚合 min/max 格子日；进度条分支卡按日期配色）
  craftSchedules: CraftSchedule[]
}

// 工种排期日期区间（无格子的工种无记录）
export interface CraftSchedule {
  craft: string
  // 最早格子日（yyyy-MM-dd）
  startDate: string | null
  // 最晚格子日（yyyy-MM-dd）
  endDate: string | null
}

// 需求操作日志项
export interface ReqLogItem {
  id: number
  operatorName: string
  // 动作码：如 CREATE / UPDATE / CANCEL / ARCHIVE
  action: string
  // 操作备注摘要
  remark: string
  // 验收轮次（需求侧一般为空，Bug 验收轮次用）
  roundNo: number | null
  createdAt: string
}

// 需求列表查询参数（可选条件为空时不传）
export interface ReqListParams {
  projectId?: number
  status?: string
  priority?: number
  plannerId?: number
  keyword?: string
  page: number
  size: number
}

// 新增需求入参
export interface CreateReqParams {
  projectId: number
  title: string
  priority: 1 | 2 | 3
  description?: string
  plannerId?: number
  participants?: ReqParticipantInput[]
  attachments?: ReqAttachment[]
}

// 编辑需求入参（updatedAt 必填，乐观锁）
export interface UpdateReqParams {
  title?: string
  priority?: 1 | 2 | 3
  description?: string
  plannerId?: number | null
  participants?: ReqParticipantInput[]
  attachments?: ReqAttachment[]
  updatedAt: number
}

// 分页查询需求列表
export function listRequirements(params: ReqListParams) {
  return request.get<ApiResponse<PageResult<ReqListItem>>>('/requirements', { params })
}

// 新增需求
export function createRequirement(data: CreateReqParams) {
  return request.post<ApiResponse<void>>('/requirements', data)
}

// 查询需求详情
export function getRequirement(id: number) {
  return request.get<ApiResponse<ReqDetail>>(`/requirements/${id}`)
}

// 查询需求操作日志（按时间倒序）
export function getRequirementLogs(id: number) {
  return request.get<ApiResponse<ReqLogItem[]>>(`/requirements/${id}/logs`)
}

// 编辑需求（乐观锁冲突时后端返回 code=409）
export function updateRequirement(id: number, data: UpdateReqParams) {
  return request.put<ApiResponse<void>>(`/requirements/${id}`, data)
}

// 取消需求（原因必填，终态）
export function cancelRequirement(id: number, data: { reason: string }) {
  return request.post<ApiResponse<void>>(`/requirements/${id}/cancel`, data)
}

// 归档需求（仅待归档状态可归档）
export function archiveRequirement(id: number) {
  return request.post<ApiResponse<void>>(`/requirements/${id}/archive`)
}
