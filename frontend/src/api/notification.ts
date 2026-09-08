import request, { type ApiResponse } from './request'
import type { PageResult } from './user'

// 站内通知项
export interface NotificationItem {
  id: number
  // 通知类型码：如 REQ_PARTICIPANT_ADDED / BUG_ASSIGNED
  type: string
  title: string
  content: string
  // 跳转目标类型：如 REQUIREMENT
  bizType: string
  bizId: number
  // 是否已读：0未读 1已读
  isRead: 0 | 1
  createdAt: string
}

// 分页查询通知列表
export function listNotifications(params: { page: number; size: number }) {
  return request.get<ApiResponse<PageResult<NotificationItem>>>('/notifications', { params })
}

// 查询未读数量
export function getUnreadCount() {
  return request.get<ApiResponse<{ count: number }>>('/notifications/unread-count')
}

// 全部标记已读
export function readAllNotifications() {
  return request.post<ApiResponse<void>>('/notifications/read-all')
}
