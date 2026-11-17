import { defineConfig, devices } from '@playwright/test'

/**
 * Playwright 配置：E2E 需要一套已启动的系统（前端 + 后端 + MySQL/Redis）。
 *
 * 默认不指定 baseURL 时指向 http://localhost:8081（docker compose 前端端口）；
 * 运行时通过 E2E_BASE_URL 覆盖，例如：
 *   $env:E2E_BASE_URL='http://localhost:8081'; npm run test:e2e
 *
 * 为避免误连本地环境，用例内部会检查 E2E_BASE_URL，未设置时整体跳过。
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
