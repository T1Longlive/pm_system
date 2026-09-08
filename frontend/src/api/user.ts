import request, { type ApiResponse } from './request'

// MyBatis-Plus 分页返回结构
export interface PageResult<T> {
  records: T[]
  total: number
  current: number
  size: number
}

// 人员列表项
export interface UserItem {
  id: number
  username: string
  realName: string
  // 是否管理员：1是 0否
  isAdmin: number
  // 状态：1正常 0停用
  status: number
  createdAt: string
}

// 人员列表查询参数（keyword/status 为空时不传）
export interface UserListParams {
  keyword?: string
  status?: number
  page: number
  size: number
}

// 新增人员入参（password 留空则后端默认 Pm@123456）
export interface CreateUserParams {
  username: string
  realName: string
  password?: string
}

// 人员下拉选项
export interface UserOption {
  id: number
  realName: string
  username: string
}

// 分页查询人员列表
export function listUsers(params: UserListParams) {
  return request.get<ApiResponse<PageResult<UserItem>>>('/users', { params })
}

// 新增人员
export function createUser(data: CreateUserParams) {
  return request.post<ApiResponse<void>>('/users', data)
}

// 编辑人员（仅修改姓名）
export function updateUser(id: number, data: { realName: string }) {
  return request.put<ApiResponse<void>>(`/users/${id}`, data)
}

// 重置密码（password 留空则后端默认 Pm@123456）
export function resetPassword(id: number, data: { password?: string }) {
  return request.post<ApiResponse<void>>(`/users/${id}/reset-password`, data)
}

// 启用 / 停用账号
export function updateUserStatus(id: number, data: { status: number }) {
  return request.post<ApiResponse<void>>(`/users/${id}/status`, data)
}

// 人员下拉选项（添加项目成员等场景使用）
export function userOptions() {
  return request.get<ApiResponse<UserOption[]>>('/users/options')
}
