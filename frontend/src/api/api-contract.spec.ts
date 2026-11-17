import { beforeEach, describe, expect, it, vi } from 'vitest'

/**
 * API 模块契约测试：只验证「URL / HTTP 方法 / 参数形态」，不关心返回值。
 * 用 mock 掉 request 实例的方式，防止路径拼错、方法用错、参数被吞等低级回归。
 */

const req = vi.hoisted(() => ({
  get: vi.fn().mockResolvedValue({}),
  post: vi.fn().mockResolvedValue({}),
  put: vi.fn().mockResolvedValue({}),
  delete: vi.fn().mockResolvedValue({})
}))

vi.mock('./request', () => ({ default: req }))

import * as authApi from './auth'
import * as userApi from './user'
import * as projectApi from './project'
import * as requirementApi from './requirement'
import * as bugApi from './bug'
import * as scheduleApi from './schedule'
import * as calendarApi from './calendar'
import * as notificationApi from './notification'
import * as dashboardApi from './dashboard'
import * as reportsApi from './reports'
import * as searchApi from './search'
import * as operationLogApi from './operationLogs'
import * as fileApi from './file'

beforeEach(() => {
  vi.clearAllMocks()
})

describe('认证 / 人员', () => {
  it('登录、改密、当前用户', () => {
    authApi.login({ username: 'admin', password: 'pwd' })
    authApi.changePassword({ oldPassword: 'a', newPassword: 'b' })
    authApi.me()

    expect(req.post).toHaveBeenNthCalledWith(1, '/auth/login', { username: 'admin', password: 'pwd' })
    expect(req.post).toHaveBeenNthCalledWith(2, '/auth/change-password',
      { oldPassword: 'a', newPassword: 'b' })
    expect(req.get).toHaveBeenCalledWith('/auth/me')
  })

  it('人员管理六个端点', () => {
    userApi.listUsers({ keyword: '张', status: 1, page: 1, size: 10 })
    userApi.createUser({ username: 'u', realName: '用户' })
    userApi.updateUser(2, { realName: '新名' })
    userApi.resetPassword(2, {})
    userApi.updateUserStatus(2, { status: 0 })
    userApi.userOptions()

    expect(req.get).toHaveBeenNthCalledWith(1, '/users',
      { params: { keyword: '张', status: 1, page: 1, size: 10 } })
    expect(req.post).toHaveBeenNthCalledWith(1, '/users', { username: 'u', realName: '用户' })
    expect(req.put).toHaveBeenCalledWith('/users/2', { realName: '新名' })
    expect(req.post).toHaveBeenNthCalledWith(2, '/users/2/reset-password', {})
    expect(req.post).toHaveBeenNthCalledWith(3, '/users/2/status', { status: 0 })
    expect(req.get).toHaveBeenNthCalledWith(2, '/users/options')
  })
})

describe('项目', () => {
  it('列表 / 创建 / 详情 / 编辑', () => {
    projectApi.listProjects({ page: 1, size: 10 })
    projectApi.createProject({ name: '项目', code: 'ACE' })
    projectApi.getProject(3)
    projectApi.updateProject(3, { name: '新名' })

    expect(req.get).toHaveBeenNthCalledWith(1, '/projects', { params: { page: 1, size: 10 } })
    expect(req.post).toHaveBeenCalledWith('/projects', { name: '项目', code: 'ACE' })
    expect(req.get).toHaveBeenNthCalledWith(2, '/projects/3')
    expect(req.put).toHaveBeenCalledWith('/projects/3', { name: '新名' })
  })

  it('成员移除区分「删单工种」与「删全部」', () => {
    projectApi.addProjectMember(3, { userId: 2, crafts: ['FE', 'QA'] })
    projectApi.removeProjectMemberCraft(3, 2, 'FE')
    projectApi.removeProjectMember(3, 2)

    expect(req.post).toHaveBeenCalledWith('/projects/3/members',
      { userId: 2, crafts: ['FE', 'QA'] })
    expect(req.delete).toHaveBeenNthCalledWith(1, '/projects/3/members/2',
      { params: { craft: 'FE' } })
    expect(req.delete).toHaveBeenNthCalledWith(2, '/projects/3/members/2')
  })
})

describe('需求', () => {
  it('列表 / 创建 / 详情 / 日志', () => {
    requirementApi.listRequirements({ projectId: 1, page: 1, size: 10 })
    requirementApi.createRequirement({ projectId: 1, title: '需求', priority: 2 })
    requirementApi.getRequirement(5)
    requirementApi.getRequirementLogs(5)

    expect(req.get).toHaveBeenNthCalledWith(1, '/requirements',
      { params: { projectId: 1, page: 1, size: 10 } })
    expect(req.post).toHaveBeenCalledWith('/requirements',
      { projectId: 1, title: '需求', priority: 2 })
    expect(req.get).toHaveBeenNthCalledWith(2, '/requirements/5')
    expect(req.get).toHaveBeenNthCalledWith(3, '/requirements/5/logs')
  })

  it('编辑 / 取消 / 归档', () => {
    requirementApi.updateRequirement(5, { title: '改', updatedAt: 123 })
    requirementApi.cancelRequirement(5, { reason: '作废' })
    requirementApi.archiveRequirement(5)

    expect(req.put).toHaveBeenCalledWith('/requirements/5', { title: '改', updatedAt: 123 })
    expect(req.post).toHaveBeenNthCalledWith(1, '/requirements/5/cancel', { reason: '作废' })
    expect(req.post).toHaveBeenNthCalledWith(2, '/requirements/5/archive')
  })
})

describe('Bug', () => {
  it('列表：archived 合并进查询参数', () => {
    bugApi.listBugs({ projectId: 1, page: 1, size: 1000 }, 1)

    expect(req.get).toHaveBeenCalledWith('/bugs',
      { params: { projectId: 1, page: 1, size: 1000, archived: 1 } })
  })

  it('创建 / 编辑 / 删除 / 归档 / 重开', () => {
    bugApi.createBug({ projectId: 1 })
    bugApi.updateBug(2, { feature: '登录' })
    bugApi.deleteBug(2)
    bugApi.archiveBug(2)
    bugApi.reopenBug(2)

    expect(req.post).toHaveBeenNthCalledWith(1, '/bugs', { projectId: 1 })
    expect(req.put).toHaveBeenCalledWith('/bugs/2', { feature: '登录' })
    expect(req.delete).toHaveBeenCalledWith('/bugs/2')
    expect(req.post).toHaveBeenNthCalledWith(2, '/bugs/2/archive')
    expect(req.post).toHaveBeenNthCalledWith(3, '/bugs/2/reopen')
  })

  it('截图：追加包一层 images，删除带附件 id', () => {
    const image = { url: '/upload/a.png', fileName: 'a.png', fileType: 'IMAGE' as const, fileSize: 10 }
    bugApi.addBugImages(2, [image])
    bugApi.deleteBugImage(2, 9)

    expect(req.post).toHaveBeenCalledWith('/bugs/2/images', { images: [image] })
    expect(req.delete).toHaveBeenCalledWith('/bugs/2/images/9')
  })
})

describe('排期', () => {
  it('月表 / 视图 / 添加 / 移除', () => {
    scheduleApi.listScheduleMonths()
    scheduleApi.createScheduleMonth({ year: 2026, month: 12 })
    scheduleApi.getScheduleMonthView(4)
    scheduleApi.addRequirementToMonth(4, { requirementId: 5 })
    scheduleApi.removeRequirementFromMonth(4, 5)

    expect(req.get).toHaveBeenNthCalledWith(1, '/schedule/months')
    expect(req.post).toHaveBeenNthCalledWith(1, '/schedule/months', { year: 2026, month: 12 })
    expect(req.get).toHaveBeenNthCalledWith(2, '/schedule/months/4/view')
    expect(req.post).toHaveBeenNthCalledWith(2, '/schedule/months/4/requirements',
      { requirementId: 5 })
    expect(req.delete).toHaveBeenCalledWith('/schedule/months/4/requirements/5')
  })

  it('保存行 / 复制到下月 / 未完成列表', () => {
    scheduleApi.saveScheduleRow(6, { plannedEffort: 1, cells: { '2026-12-01': 0.5 }, updatedAt: 1 })
    scheduleApi.copyRequirementToNextMonth(6)
    scheduleApi.listIncompleteRequirements()

    expect(req.put).toHaveBeenCalledWith('/schedule/rows/6',
      { plannedEffort: 1, cells: { '2026-12-01': 0.5 }, updatedAt: 1 })
    expect(req.post).toHaveBeenCalledWith('/schedule/rows/6/to-next-month')
    expect(req.get).toHaveBeenCalledWith('/schedule/incomplete')
  })
})

describe('日历 / 通知 / 工作台 / 报表 / 搜索 / 操作日志', () => {
  it('日历查询与维护', () => {
    calendarApi.getCalendar(2026, 11)
    calendarApi.updateCalendarDay('2026-11-18', { type: 'HOLIDAY', name: '测试节' })

    expect(req.get).toHaveBeenCalledWith('/calendar', { params: { year: 2026, month: 11 } })
    expect(req.put).toHaveBeenCalledWith('/calendar/2026-11-18',
      { type: 'HOLIDAY', name: '测试节' })
  })

  it('通知三个端点', () => {
    notificationApi.listNotifications({ page: 1, size: 10 })
    notificationApi.getUnreadCount()
    notificationApi.readAllNotifications()

    expect(req.get).toHaveBeenNthCalledWith(1, '/notifications', { params: { page: 1, size: 10 } })
    expect(req.get).toHaveBeenNthCalledWith(2, '/notifications/unread-count')
    expect(req.post).toHaveBeenCalledWith('/notifications/read-all')
  })

  it('工作台两个端点', () => {
    dashboardApi.getDashboardSummary()
    dashboardApi.listMyRequirements()

    expect(req.get).toHaveBeenNthCalledWith(1, '/dashboard/summary')
    expect(req.get).toHaveBeenNthCalledWith(2, '/dashboard/my-requirements')
  })

  it('报表三个端点', () => {
    reportsApi.getRequirementStatus()
    reportsApi.getWorkload(6)
    reportsApi.getProjectOverview()

    expect(req.get).toHaveBeenNthCalledWith(1, '/reports/requirement-status')
    expect(req.get).toHaveBeenNthCalledWith(2, '/reports/workload', { params: { months: 6 } })
    expect(req.get).toHaveBeenNthCalledWith(3, '/reports/project-overview')
  })

  it('全局搜索与操作日志', () => {
    searchApi.globalSearch('登录')
    operationLogApi.pageOperationLogs({ bizType: 'BUG', page: 1, size: 20 })
    operationLogApi.listLogOperators()

    expect(req.get).toHaveBeenNthCalledWith(1, '/search', { params: { keyword: '登录' } })
    expect(req.get).toHaveBeenNthCalledWith(2, '/operation-logs',
      { params: { bizType: 'BUG', page: 1, size: 20 } })
    expect(req.get).toHaveBeenNthCalledWith(3, '/operation-logs/operators')
  })
})

describe('文件上传', () => {
  it('以 FormData 提交 file 字段', () => {
    const file = new File(['x'], 'a.png', { type: 'image/png' })
    fileApi.uploadFile(file)

    expect(req.post).toHaveBeenCalledTimes(1)
    const [url, body] = req.post.mock.calls[0]
    expect(url).toBe('/files/upload')
    expect(body).toBeInstanceOf(FormData)
    expect((body as FormData).get('file')).toBe(file)
  })
})
