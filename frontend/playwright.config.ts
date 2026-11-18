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
 *
 * 多浏览器：默认只跑 Chromium；CI 安装 Firefox/WebKit 后设置
 * E2E_MULTI_BROWSER=1，仅对 smoke.spec.ts 做跨浏览器冒烟，避免全量用例拖慢流水线。
 */
const multiBrowser = process.env.E2E_MULTI_BROWSER === '1'

export default defineConfig({
  testDir: './e2e',
  timeout: 30_000,
  expect: { timeout: 5_000 },
  fullyParallel: false,
  retries: process.env.CI ? 1 : 0,
  workers: process.env.CI ? 1 : undefined,
  reporter: [
    ['list'],
    ['html', { open: 'never', outputFolder: 'playwright-report' }],
    // 机器可读结果：供 scripts/test-summary.mjs 汇总 expected/unexpected/flaky/skipped
    ['json', { outputFile: 'reports/e2e-results.json' }]
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
    },
    ...(multiBrowser
      ? [
          {
            name: 'firefox-smoke',
            use: { ...devices['Desktop Firefox'] },
            testMatch: /smoke\.spec\.ts/
          },
          {
            name: 'webkit-smoke',
            use: { ...devices['Desktop Safari'] },
            testMatch: /smoke\.spec\.ts/
          }
        ]
      : [])
  ]
})
