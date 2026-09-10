import request, { type ApiResponse } from './request'

// 全局搜索结果：需求/Bug/项目/人员 四类各前 5 条
export interface SearchResult {
  requirements: SearchReqItem[]
  bugs: SearchBugItem[]
  projects: SearchProjectItem[]
  users: SearchUserItem[]
}

// 需求命中项
export interface SearchReqItem {
  id: number
  reqNo: string
  title: string
  projectName: string
  // PENDING/DEV/TEST/ARCHIVING/ARCHIVED/CANCELLED
  status: string
}

// Bug 命中项
export interface SearchBugItem {
  id: number
  bugNo: string
  projectId: number
  feature: string
  projectName: string
  // 空=未处理/DONE/HOLD/SKIP
  handleStatus: string
  // 0总表 1归档表
  archived: number
}

// 项目命中项
export interface SearchProjectItem {
  id: number
  name: string
  code: string
}

// 人员命中项（仅姓名/用户名）
export interface SearchUserItem {
  id: number
  realName: string
  username: string
}

// 全局搜索（keyword 空白时返回全空结果）
export function globalSearch(keyword: string) {
  return request.get<ApiResponse<SearchResult>>('/search', { params: { keyword } })
}
