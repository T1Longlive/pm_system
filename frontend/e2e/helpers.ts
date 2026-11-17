import { expect, type APIRequestContext, type Page } from '@playwright/test'

/**
 * E2E 公共助手：API 造数 + UI 登录。
 *
 * 设计原则：
 * · 造数只走真实 HTTP API（与前端同源 /api 代理），不直连数据库，避免测试依赖库表细节；
 * · 每个 spec 用随机项目缩写/用户名，避免与既有数据冲突；
 * · 管理员账号默认取 init.sql 种子账号，可用 E2E_USERNAME / E2E_PASSWORD 覆盖。
 */

export const ADMIN_USERNAME = process.env.E2E_USERNAME || 'admin'
export const ADMIN_PASSWORD = process.env.E2E_PASSWORD || 'Pm@123456'
/** 普通成员改密后的登录密码 */
export const MEMBER_PASSWORD = 'E2e@123456'

export interface LoginResult {
  token: string
  userId: number
  username: string
  isAdmin: number
  isPm: number
  forceChangePwd: number
}

export interface SeededFlow {
  adminToken: string
  projectId: number
  projectCode: string
  projectName: string
  requirementId: number
  requirementTitle: string
  /** 第二个需求：只建不进月表，供「从需求池添加」弹窗使用 */
  pickableRequirementId: number
  pickableRequirementTitle: string
  memberId: number
  memberUsername: string
  memberRealName: string
  monthId: number
  monthLabel: string
}

export async function loginByApi(
  request: APIRequestContext,
  username = ADMIN_USERNAME,
  password = ADMIN_PASSWORD
): Promise<LoginResult> {
  const response = await request.post('/api/auth/login', { data: { username, password } })
  expect(response.ok()).toBeTruthy()
  const body = await response.json()
  expect(body.code, `登录失败：${body.message}`).toBe(200)
  return body.data as LoginResult
}

async function apiCall<T>(
  request: APIRequestContext,
  method: 'get' | 'post' | 'put' | 'delete',
  path: string,
  token: string,
  data?: unknown
): Promise<T> {
  const response = await request[method](path, {
    headers: { Authorization: `Bearer ${token}` },
    data
  })
  expect(response.ok()).toBeTruthy()
  const body = await response.json()
  expect(body.code, `${method.toUpperCase()} ${path} 失败：${body.message}`).toBe(200)
  return body.data as T
}

export const apiGet = <T>(request: APIRequestContext, path: string, token: string) =>
  apiCall<T>(request, 'get', path, token)
export const apiPost = <T>(request: APIRequestContext, path: string, token: string, data?: unknown) =>
  apiCall<T>(request, 'post', path, token, data)
export const apiPut = <T>(request: APIRequestContext, path: string, token: string, data?: unknown) =>
  apiCall<T>(request, 'put', path, token, data)

function randomLetters(length: number): string {
  return Array.from({ length }, () =>
    String.fromCharCode(65 + Math.floor(Math.random() * 26))).join('')
}

interface PageResult<T> {
  records: T[]
  total: number
}

/**
 * 造一条完整主链路数据：
 * 项目 → 成员（PLANNER/FE/QA）→ 需求（含参与人）→ 当月排期表 → 需求加入月表。
 * 同时把成员密码改为 MEMBER_PASSWORD，供普通用户权限用例登录。
 */
export async function seedCoreFlow(request: APIRequestContext): Promise<SeededFlow> {
  const admin = await loginByApi(request)
  const suffix = randomLetters(3)
  // 项目缩写仅允许 2-6 位大写字母（不能带数字）
  const projectCode = `EPM${suffix}`
  const projectName = `E2E项目${suffix}`
  const requirementTitle = `E2E需求${suffix}`
  const memberUsername = `e2e${suffix.toLowerCase()}`
  const memberRealName = `E2E成员${suffix}`

  await apiPost(request, '/api/projects', admin.token, { name: projectName, code: projectCode })
  const projects = await apiGet<PageResult<{ id: number; code: string }>>(request,
    `/api/projects?keyword=${encodeURIComponent(projectName)}&page=1&size=10`, admin.token)
  const project = projects.records.find((item) => item.code === projectCode)
  expect(project, '项目创建后应能在列表中查到').toBeTruthy()

  await apiPost(request, '/api/users', admin.token,
    { username: memberUsername, realName: memberRealName })
  const users = await apiGet<PageResult<{ id: number }>>(request,
    `/api/users?keyword=${memberUsername}&page=1&size=10`, admin.token)
  const member = users.records[0]
  expect(member, '成员创建后应能在人员列表中查到').toBeTruthy()

  await apiPost(request, `/api/projects/${project!.id}/members`, admin.token,
    { userId: member.id, crafts: ['PLANNER', 'FE', 'QA'] })

  const requirementId = await apiPost<number>(request, '/api/requirements', admin.token, {
    projectId: project!.id,
    title: requirementTitle,
    priority: 1,
    plannerId: member.id,
    participants: [
      { userId: member.id, craft: 'PLANNER' },
      { userId: member.id, craft: 'FE' }
    ]
  })

  // 第二个需求不加入月表：用于验证「从需求池添加」
  const pickableRequirementTitle = `${requirementTitle}-待加入`
  const pickableRequirementId = await apiPost<number>(request, '/api/requirements', admin.token, {
    projectId: project!.id,
    title: pickableRequirementTitle,
    priority: 2,
    plannerId: member.id,
    participants: [{ userId: member.id, craft: 'FE' }]
  })

  const now = new Date()
  const year = now.getFullYear()
  const month = now.getMonth() + 1
  let scheduleMonth: { id: number; year: number; month: number }
  try {
    scheduleMonth = await apiPost(request, '/api/schedule/months', admin.token, { year, month })
  } catch {
    // 目标环境已存在该月表（如远程共享环境）：直接复用
    const months = await apiGet<Array<{ id: number; year: number; month: number }>>(request,
      '/api/schedule/months', admin.token)
    scheduleMonth = months.find((item) => item.year === year && item.month === month)!
    expect(scheduleMonth, '当月排期表应存在或可创建').toBeTruthy()
  }
  await apiPost(request, `/api/schedule/months/${scheduleMonth.id}/requirements`, admin.token,
    { requirementId })

  // 新成员首登强制改密：先登录再改密，后续 UI 用新密码
  const memberLogin = await loginByApi(request, memberUsername, ADMIN_PASSWORD)
  await apiPost(request, '/api/auth/change-password', memberLogin.token,
    { oldPassword: ADMIN_PASSWORD, newPassword: MEMBER_PASSWORD })

  return {
    adminToken: admin.token,
    projectId: project!.id,
    projectCode,
    projectName,
    requirementId,
    requirementTitle,
    pickableRequirementId,
    pickableRequirementTitle,
    memberId: member.id,
    memberUsername,
    memberRealName,
    monthId: scheduleMonth.id,
    monthLabel: `${year}年${month}月`
  }
}

/** UI 登录：以登录按钮 class 定位，避免按钮文案中的空格差异 */
export async function loginViaUi(page: Page, username: string, password: string): Promise<void> {
  await page.goto('/login')
  await page.getByPlaceholder('用户名').fill(username)
  await page.getByPlaceholder('密码').fill(password)
  await page.locator('.login-btn').click()
  await expect(page).toHaveURL(/\/(dashboard|change-password)/, { timeout: 10_000 })
}
