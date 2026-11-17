import { expect, request as apiRequest, test, type APIRequestContext } from '@playwright/test'
import { ADMIN_PASSWORD, ADMIN_USERNAME, loginViaUi, seedCoreFlow, type SeededFlow } from './helpers'

/**
 * 主链路 E2E：API 造数（项目/成员/需求/月表）→ UI 验证
 * 工作台 → 需求列表/详情 → 排期矩阵 → Bug 新增 → 操作日志。
 *
 * 串行执行：同一份数据被多个用例顺序消费；每个用例独立浏览器上下文、独立登录。
 */
test.skip(!process.env.E2E_BASE_URL, '未设置 E2E_BASE_URL，跳过 E2E')
test.describe.configure({ mode: 'serial' })

let api: APIRequestContext
let data: SeededFlow

test.beforeAll(async () => {
  api = await apiRequest.newContext({ baseURL: process.env.E2E_BASE_URL })
  data = await seedCoreFlow(api)
})

test.afterAll(async () => {
  await api.dispose()
})

test('工作台：渲染统计卡片', async ({ page }) => {
  await loginViaUi(page, ADMIN_USERNAME, ADMIN_PASSWORD)

  await expect(page.getByText('未读通知')).toBeVisible()
  await expect(page.getByText('参与需求')).toBeVisible()
  await expect(page.getByText('本月排期工时')).toBeVisible()
  await expect(page.getByText('待处理 Bug')).toBeVisible()
})

test('需求：列表可见造数需求，点击进入详情并展示进度节点', async ({ page }) => {
  await loginViaUi(page, ADMIN_USERNAME, ADMIN_PASSWORD)
  await page.goto('/requirements')

  // 造数里还有一个「原标题-待加入」的需求，必须用精确匹配避免 strict mode 命中两个
  const titleButton = page.getByRole('button', { name: data.requirementTitle, exact: true })
  await expect(titleButton).toBeVisible()
  await titleButton.click()

  await expect(page).toHaveURL(new RegExp(`/requirements/${data.requirementId}`))
  await expect(page.getByText(data.requirementTitle).first()).toBeVisible()
  await expect(page.getByText('需求建立').first()).toBeVisible()
})

test('排期：当月月表存在且包含造数需求行', async ({ page }) => {
  await loginViaUi(page, ADMIN_USERNAME, ADMIN_PASSWORD)
  await page.goto('/schedule')

  await expect(page.getByText(data.monthLabel).first()).toBeVisible()
  await expect(page.getByText(data.requirementTitle).first()).toBeVisible()
})

test('Bug：选择项目后新增一行并自动生成编号', async ({ page }) => {
  await loginViaUi(page, ADMIN_USERNAME, ADMIN_PASSWORD)
  await page.goto('/bugs')

  await page.locator('.toolbar-project').click()
  await page.locator('.el-select-dropdown__item')
    .filter({ hasText: data.projectName }).click()
  await expect(page.getByText('添加Bug')).toBeVisible()

  await page.getByRole('button', { name: '添加Bug' }).click()
  // vxe-table 固定列会复制渲染单元格，同一编号可能出现多个节点，取第一个断言可见即可
  await expect(page.getByText(`BUG-${data.projectCode}-0001`).first()).toBeVisible()
})

test('操作日志：能看到造数产生的「创建需求」记录', async ({ page }) => {
  await loginViaUi(page, ADMIN_USERNAME, ADMIN_PASSWORD)
  await page.goto('/operation-logs')

  await expect(page.getByText('创建需求').first()).toBeVisible()
})
