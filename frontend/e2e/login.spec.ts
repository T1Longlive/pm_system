import { expect, test } from '@playwright/test'
import { ADMIN_PASSWORD, ADMIN_USERNAME, loginViaUi } from './helpers'

/**
 * 登录与路由守卫冒烟。
 *
 * 门禁：必须显式设置 E2E_BASE_URL 才执行，避免在 CI/本地误连环境。
 * 本地完整环境可用 `npm run test:e2e:local` 一键拉起。
 */
test.skip(!process.env.E2E_BASE_URL, '未设置 E2E_BASE_URL，跳过 E2E')

test('登录页可访问并渲染表单', async ({ page }) => {
  await page.goto('/login')

  await expect(page.getByText('项目管理系统').first()).toBeVisible()
  await expect(page.getByPlaceholder('用户名')).toBeVisible()
  await expect(page.getByPlaceholder('密码')).toBeVisible()
  await expect(page.locator('.login-btn')).toBeVisible()
})

test('未登录访问业务页 → 重定向到登录页', async ({ page }) => {
  await page.goto('/requirements')

  await expect(page).toHaveURL(/\/login/)
})

test('管理员登录后进入工作台并展示管理菜单', async ({ page }) => {
  await loginViaUi(page, ADMIN_USERNAME, ADMIN_PASSWORD)

  await expect(page).toHaveURL(/\/dashboard/)
  await expect(page.locator('.el-menu').getByText('工作台')).toBeVisible()
  await expect(page.locator('.el-menu').getByText('日历管理')).toBeVisible()
})
