import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright 配置：E2E 需要一套已启动的系统（前端 + 后端 + MySQL/Redis）。
 *
 * 两种跑法：
 * 1. 本地一键（推荐）：`npm run test:e2e:local`
 *    自动用 docker-compose.e2e.yml 起 MySQL/Redis，再起后端(18080)与前端(5173)，
 *    跑完自动清理；脚本会注入 E2E_BASE_URL=http://localhost:5173。
 * 2. 连已有环境：显式设置 E2E_BASE_URL（如 http://localhost:8081），
 *    然后 `npx playwright test`；未设置时用例整体跳过，避免误连环境。
 */
export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  fullyParallel: false,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: 'playwright-report' }]
  ],
  use: {
    baseURL: process.env.E2E_BASE_URL || 'http://localhost:8081',
    trace: 'on-first-retry',
    screenshot: 'only-on-failure',
    video: 'retain-on-failure'
  },
  projects: [
    {
      name: 'chromium',
      use: { ...devices['Desktop Chrome'] }
    }
  ]
})
