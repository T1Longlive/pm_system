import request, { type ApiResponse } from './request'
import type { PageResult } from './user'

// Bug 问题截图（attachment 表 biz_type=BUG，返回时带 id）
export interface BugImage {
  id?: number
  url: string
  fileName: string
  fileType: 'IMAGE'
  fileSize: number
}

// Bug 行（Excel 化编辑模型，字段均可留空）
export interface BugListItem {
  id: number
  bugNo: string
  projectId: number
  projectName: string
  // 发现版本
  version: string | null
  // 功能模块
  feature: string | null
  // 问题描述
  description: string | null
  // 优先级：1高 2中 3低，可空
  priority: 1 | 2 | 3 | null
  ownerId: number | null
  ownerName: string | null
  // 处理进度：null=未处理 / DONE已处理 / HOLD暂不处理 / SKIP不处理
  handleStatus: 'DONE' | 'HOLD' | 'SKIP' | null
  testerId: number | null
  testerName: string | null
  // 验收进度：null=未验收 / PASSED验收通过 / REJECTED验收不通过
  acceptStatus: 'PASSED' | 'REJECTED' | null
  // 处理方案
  solution: string | null
  // 开发备注
  devRemark: string | null
  // 测试备注
  testRemark: string | null
  // 问题视频链接
  videoUrl: string | null
  // 视频名称（显示用）
  videoName: string | null
  // 创建人 ID
  createdBy: number
  creatorName: string
  createdAt: string
  updatedAt: number
  // 0总表（Bug 列表） 1归档表（归档表）
  archived: 0 | 1
  // 归档时间/归档人（归档表展示）
  archivedAt: string | null
  archivedByName: string | null
  // 问题截图（按 id 升序，第一张即缩略图）
  images: BugImage[]
  // 我在该 Bug 所属项目中的角色：PM 或工种码；空数组 = 非项目成员
  myRole: string[]
}

// Bug 列表查询参数（项目必选；archived 0列表/1归档；一页全量 size 上限 1000）
export interface BugListParams {
  projectId: number
  archived?: number
  keyword?: string
  page: number
  size: number
}

// 编辑 Bug 入参（单元格逐格部分更新）
// 清空哨兵：不传/null=不修改；字符串 ''=清空；数值/ID 0=清空
export interface UpdateBugParams {
  version?: string
  feature?: string
  description?: string
  priority?: 0 | 1 | 2 | 3
  ownerId?: number
  testerId?: number
  handleStatus?: '' | 'DONE' | 'HOLD' | 'SKIP'
  acceptStatus?: '' | 'PASSED' | 'REJECTED'
  solution?: string
  devRemark?: string
  testRemark?: string
  videoUrl?: string
  videoName?: string
}

// 分页查询 Bug 列表（archived=0 总表 / 1 归档表）
export function listBugs(params: BugListParams, archived: 0 | 1) {
  return request.get<ApiResponse<PageResult<BugListItem>>>('/bugs', {
    params: { ...params, archived }
  })
}

// 新增 Bug（编号自动生成、测试人员=当前用户），返回新行完整 VO
export function createBug(data: { projectId: number }) {
  return request.post<ApiResponse<BugListItem>>('/bugs', data)
}

// 编辑 Bug（单元格逐格保存）
export function updateBug(id: number, data: UpdateBugParams) {
  return request.put<ApiResponse<void>>(`/bugs/${id}`, data)
}

// 删除 Bug（所有人可用，归档行也可删）
export function deleteBug(id: number) {
  return request.delete<ApiResponse<void>>(`/bugs/${id}`)
}

// 归档 Bug（项目经理或管理员，无状态限制）
export function archiveBug(id: number) {
  return request.post<ApiResponse<void>>(`/bugs/${id}/archive`)
}

// 重新打开 Bug（项目经理或管理员）：回总表并置空处理进度与验收进度
export function reopenBug(id: number) {
  return request.post<ApiResponse<void>>(`/bugs/${id}/reopen`)
}

// 批量追加问题截图，返回新附件 id 列表（与入参顺序一致）
export function addBugImages(id: number, images: BugImage[]) {
  return request.post<ApiResponse<number[]>>(`/bugs/${id}/images`, { images })
}

// 删除单张问题截图
export function deleteBugImage(id: number, attachmentId: number) {
  return request.delete<ApiResponse<void>>(`/bugs/${id}/images/${attachmentId}`)
}
