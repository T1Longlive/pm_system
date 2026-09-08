import request, { type ApiResponse } from './request'
import type { PageResult } from './user'

// 项目列表项
export interface ProjectItem {
  id: number
  code: string
  name: string
  description: string
  creatorId: number
  creatorName: string
  memberCount: number
  // 我在项目内的角色：PM（项目经理）或工种码，可多个
  myRole: string[]
}

// 项目成员
export interface ProjectMember {
  userId: number
  realName: string
  username: string
  // 该成员在项目内兼任的工种码列表
  crafts: string[]
}

// 项目详情数据
export interface ProjectDetailData {
  id: number
  code: string
  name: string
  description: string
  creatorId: number
  creatorName: string
  createdAt: string
  myRole: string[]
  members: ProjectMember[]
}

// 项目列表查询参数（keyword 为空时不传）
export interface ProjectListParams {
  keyword?: string
  page: number
  size: number
}

// 创建项目入参
export interface CreateProjectParams {
  name: string
  // 项目缩写：2-6 位大写字母，全局唯一
  code: string
  description?: string
}

// 更新项目入参（缩写创建后不可修改）
export interface UpdateProjectParams {
  name: string
  description?: string
}

// 添加项目成员入参（同一用户可一次分配多个工种）
export interface AddMemberParams {
  userId: number
  crafts: string[]
}

// 分页查询项目列表
export function listProjects(params: ProjectListParams) {
  return request.get<ApiResponse<PageResult<ProjectItem>>>('/projects', { params })
}

// 创建项目（仅管理员）
export function createProject(data: CreateProjectParams) {
  return request.post<ApiResponse<void>>('/projects', data)
}

// 查询项目详情
export function getProject(id: number) {
  return request.get<ApiResponse<ProjectDetailData>>(`/projects/${id}`)
}

// 更新项目（名称 / 描述）
export function updateProject(id: number, data: UpdateProjectParams) {
  return request.put<ApiResponse<void>>(`/projects/${id}`, data)
}

// 添加项目成员
export function addProjectMember(id: number, data: AddMemberParams) {
  return request.post<ApiResponse<void>>(`/projects/${id}/members`, data)
}

// 移除成员的单个工种（带 craft 参数删单工种）
export function removeProjectMemberCraft(id: number, userId: number, craft: string) {
  return request.delete<ApiResponse<void>>(`/projects/${id}/members/${userId}`, { params: { craft } })
}

// 移除整个成员（不带 craft，删除该成员全部工种）
export function removeProjectMember(id: number, userId: number) {
  return request.delete<ApiResponse<void>>(`/projects/${id}/members/${userId}`)
}
