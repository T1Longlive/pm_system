import { expect, request as apiRequest, test, type APIRequestContext } from '@playwright/test'
import { MEMBER_PASSWORD, loginViaUi, seedCoreFlow, type SeededFlow } from './helpers'

/**
 * 权限 E2E：普通项目成员登录后，管理员页被路由守卫拦截，且不展示管理员菜单。
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

test('普通成员：登录成功、无管理员菜单、访问人员管理被重定向回工作台', async ({ page }) => {
  await loginViaUi(page, data.memberUsername, MEMBER_PASSWORD)

  await expect(page).toHaveURL(/\/dashboard/)
  await expect(page.locator('.el-menu').getByText('日历管理')).toHaveCount(0)

  await page.goto('/admin/users')
  await expect(page).toHaveURL(/\/dashboard/)
})
