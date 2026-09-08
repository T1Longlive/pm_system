import request, { type ApiResponse } from './request'

// 登录入参
export interface LoginParams {
  username: string
  password: string
}

// 登录返回
export interface LoginResult {
  token: string
  userId: number
  username: string
  realName: string
  // 是否管理员：1是 0否
  isAdmin: number
  // 是否需要强制修改密码（首次登录 / 管理员重置后）：1是 0否
  forceChangePwd: number
}

// 修改密码入参
export interface ChangePasswordParams {
  oldPassword: string
  newPassword: string
}

// 当前用户信息
export interface UserInfo {
  userId: number
  username: string
  realName: string
  isAdmin: number
  // 是否需要强制修改密码：1是 0否
  forceChangePwd: number
}

// 账号密码登录
export function login(data: LoginParams) {
  return request.post<ApiResponse<LoginResult>>('/auth/login', data)
}

// 修改密码（首次登录强制改密场景）
export function changePassword(data: ChangePasswordParams) {
  return request.post<ApiResponse<void>>('/auth/change-password', data)
}

// 获取当前登录用户信息
export function me() {
  return request.get<ApiResponse<UserInfo>>('/auth/me')
}
