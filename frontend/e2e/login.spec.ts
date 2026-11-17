import { expect, test } from '@playwright/test'

/**
 * E2E 冒烟：登录页可访问 + 登录主流程。
 *
 * 门禁：必须显式设置 E2E_BASE_URL 才执行，避免在 CI/本地误连环境。
 * 登录流程还需要 E2E_USERNAME / E2E_PASSWORD（建议用测试账号，不要用生产管理员）。
 */
test.skip(!process.env.E2E_BASE_URL, '未设置 E2E_BASE_URL，跳过 E2E')

test('登录页可访问并渲染表单', async ({ page }) => {
  await page.goto('/login')

  await expect(page.getByText('项目管理系统')).toBeVisible()
  await expect(page.getByPlaceholder('用户名')).toBeVisible()
  await expect(page.getByPlaceholder('密码')).toBeVisible()
  await expect(page.getByRole('button', { name: '登录' })).toBeVisible()
})

test('使用环境变量账号登录后进入工作台', async ({ page }) => {
  test.skip(!process.env.E2E_USERNAME || !process.env.E2E_PASSWORD,
    '未设置 E2E_USERNAME / E2E_PASSWORD，跳过登录流程')

  await page.goto('/login')
  await page.getByPlaceholder('用户名').fill(process.env.E2E_USERNAME!)
  await page.getByPlaceholder('密码').fill(process.env.E2E_PASSWORD!)
  await page.getByRole('button', { name: '登录' }).click()

  // 首登可能被强制改密；两种结果都视为登录成功
  await expect(page).toHaveURL(/\/(dashboard|change-password)/)
})
