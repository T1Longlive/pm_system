import { expect, request as apiRequest, test, type APIRequestContext } from '@playwright/test'
import {
  ADMIN_PASSWORD,
  ADMIN_USERNAME,
  MEMBER_PASSWORD,
  apiPost,
  loginByApi,
  loginViaUi,
  seedCoreFlow,
  type SeededFlow
} from './helpers'

/**
 * E2E 深度用例：强制改密、全局搜索、通知已读、日志筛选、需求池添加。
 * 依赖 seedCoreFlow 造的独立项目/需求/成员数据，避免与 core-flow 数据互相污染。
 */
test.skip(!process.env.E2E_BASE_URL, '未设置 E2E_BASE_URL，跳过 E2E')

let api: APIRequestContext
let data: SeededFlow

test.beforeAll(async () => {
  api = await apiRequest.newContext({ baseURL: process.env.E2E_BASE_URL })
  data = await seedCoreFlow(api)
})

test.afterAll(async () => {
  await api.dispose()
})

test('强制改密：新用户登录被引导改密，改密后进入工作台', async ({ page }) => {
  const username = `pwd${Date.now().toString(36)}`
  await apiPost(api, '/api/users', data.adminToken, { username, realName: '改密用户' })

  await loginViaUi(page, username, ADMIN_PASSWORD)
  await expect(page).toHaveURL(/\/change-password/)
  await expect(page.getByText('修改密码').first()).toBeVisible()

  await page.getByPlaceholder('原密码').fill(ADMIN_PASSWORD)
  await page.getByPlaceholder('新密码（至少 6 位）').fill(MEMBER_PASSWORD)
  await page.getByPlaceholder('确认新密码').fill(MEMBER_PASSWORD)
  await page.locator('.pwd-btn').click()

  await expect(page).toHaveURL(/\/dashboard/)
})

test('全局搜索：输入需求名称，点击结果跳转需求详情', async ({ page }) => {
  await loginViaUi(page, ADMIN_USERNAME, ADMIN_PASSWORD)

  await page.getByPlaceholder('搜索需求 / Bug / 项目 / 人员').fill(data.requirementTitle)
  // 精确匹配标题，避免命中「原标题-待加入」的第二条需求
  const result = page.locator('.search-item')
    .filter({ has: page.getByText(data.requirementTitle, { exact: true }) })
  await expect(result).toBeVisible()

  await result.click()
  await expect(page).toHaveURL(new RegExp(`/requirements/${data.requirementId}$`))
})

test('通知：成员看到未读角标，全部已读后未读点消失', async ({ page }) => {
  await loginViaUi(page, data.memberUsername, MEMBER_PASSWORD)

  await page.locator('.notice-badge').click()
  await expect(page.locator('.notice-item').first()).toBeVisible()
  await expect(page.locator('.notice-dot').first()).toBeVisible()

  await page.getByRole('button', { name: '全部已读' }).click()
  await expect(page.locator('.notice-dot')).toHaveCount(0)
})

test('操作日志：按项目与业务类型筛选', async ({ page }) => {
  await loginViaUi(page, ADMIN_USERNAME, ADMIN_PASSWORD)
  await page.goto('/operation-logs')

  // 先限定到本项目，避免与其他用例产生的日志互相干扰
  await page.locator('.f-item').nth(3).click()
  await page.locator('.el-select-dropdown__item:visible')
    .filter({ hasText: data.projectName }).click()
  await page.getByRole('button', { name: '查询' }).click()
  await expect(page.getByText('创建需求').first()).toBeVisible()

  // 业务类型切到 Bug：本项目没有 Bug 日志 → 空表
  await page.locator('.f-item').first().click()
  await page.locator('.el-select-dropdown__item:visible').filter({ hasText: 'Bug' }).click()
  await page.getByRole('button', { name: '查询' }).click()
  await expect(page.getByText('暂无数据')).toBeVisible()
})

test('需求：通过新增弹窗创建后出现在列表', async ({ page }) => {
  const title = `${data.requirementTitle}-UI创建`
  await loginViaUi(page, ADMIN_USERNAME, ADMIN_PASSWORD)
  await page.goto('/requirements')

  await page.getByRole('button', { name: '新增需求' }).click()
  const dialog = page.locator('.el-dialog').filter({ hasText: '新增需求' })
  await expect(dialog).toBeVisible()

  // 所属项目是弹窗内第一个下拉
  await dialog.locator('.el-select').first().click()
  await page.locator('.el-select-dropdown__item:visible')
    .filter({ hasText: data.projectName }).click()
  await dialog.getByPlaceholder('请输入需求名称').fill(title)
  await dialog.getByRole('button', { name: '确定' }).click()

  await expect(page.getByRole('button', { name: title, exact: true })).toBeVisible()
})

test('排期：从需求池添加未排期需求后出现在矩阵中', async ({ page }) => {
  await loginViaUi(page, ADMIN_USERNAME, ADMIN_PASSWORD)
  await page.goto('/schedule')
  await expect(page.getByText(data.monthLabel).first()).toBeVisible()

  await page.getByRole('button', { name: '从需求池添加' }).click()
  const dialog = page.locator('.el-dialog').filter({ hasText: '从需求池添加' })
  await expect(dialog.getByText(data.pickableRequirementTitle).first()).toBeVisible()

  await dialog.getByText(data.pickableRequirementTitle).first().click()
  await dialog.getByRole('button', { name: '确定' }).click()

  // 弹窗内容被 teleport 到 body，断言限定在排期页容器内，避免命中已隐藏的弹窗行
  await expect(page.locator('.schedule-page').getByText(data.pickableRequirementTitle).first())
    .toBeVisible()
})
