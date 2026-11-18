import { expect, test } from '@playwright/test'
import { ADMIN_PASSWORD, ADMIN_USERNAME, loginViaUi } from './helpers'

/**
 * 跨浏览器冒烟：只覆盖"能打开、能登录、能进工作台"三条生命线，
 * 由 Chromium / Firefox / WebKit 三个 project 共同执行（Firefox/WebKit 仅在
 * E2E_MULTI_BROWSER=1 时启用），完整业务回归仍由 Chromium 全量用例承担。
 */
test.skip(!process.env.E2E_BASE_URL, '未设置 E2E_BASE_URL，跳过 E2E')

test('跨浏览器冒烟：登录页可访问', async ({ page }) => {
  await page.goto('/login')

  await expect(page.getByText('项目管理系统').first()).toBeVisible()
  await expect(page.getByPlaceholder('用户名')).toBeVisible()
  await expect(page.getByPlaceholder('密码')).toBeVisible()
  await expect(page.locator('.login-btn')).toBeVisible()
})

test('跨浏览器冒烟：管理员登录进入工作台', async ({ page }) => {
  await loginViaUi(page, ADMIN_USERNAME, ADMIN_PASSWORD)

  await expect(page).toHaveURL(/\/dashboard/)
  await expect(page.getByText('未读通知')).toBeVisible()
})
