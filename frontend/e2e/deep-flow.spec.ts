import {
  expect,
  request as apiRequest,
  test,
  type APIRequestContext,
  type Locator,
  type Page
} from '@playwright/test'
import {
  ADMIN_PASSWORD,
  ADMIN_USERNAME,
  apiGet,
  apiPut,
  loginViaUi,
  seedCoreFlow,
  type SeededFlow
} from './helpers'

/**
 * E2E 深度用例：排期格子/工期自动保存、乐观锁冲突、需求取消、Bug 逐格编辑、
 * 管理端新增人员与登录、日历维护后排期灰显。
 *
 * 串行执行：同一份造数被顺序消费；日历用例会临时改一天类型，结束后立即还原。
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

/** 排期主表行：限定 main body，避免命中固定列复制出来的同名行 */
function scheduleRow(page: Page, title: string): Locator {
  return page.locator('.body--wrapper .vxe-body--row').filter({ hasText: title }).first()
}

interface ScheduleViewLike {
  groups: Array<{
    requirementId: number
    rows: Array<{ rowId: number; plannedEffort: number | null; updatedAt: number }>
  }>
}

test('排期：填写工期与格子自动保存，刷新后仍保留', async ({ page }) => {
  await loginViaUi(page, ADMIN_USERNAME, ADMIN_PASSWORD)
  await page.goto('/schedule')

  const row = scheduleRow(page, data.requirementTitle)
  await expect(row).toBeVisible()

  // 工期：输入后回车提交（触发整行保存）
  const effortInput = row.locator('.effort-input input')
  await effortInput.fill('1')
  const [effortResp] = await Promise.all([
    page.waitForResponse((r) => r.url().includes('/api/schedule/rows/') && r.request().method() === 'PUT'),
    effortInput.press('Enter')
  ])
  expect(effortResp.ok()).toBeTruthy()

  // 格子：点击第一个可排班日进入编辑，填 0.5 后回车保存
  const cell = row.locator('td.cell-day:not(.cell-locked)').first()
  await cell.locator('.cell-value').click()
  const cellInput = cell.locator('input.cell-input')
  await expect(cellInput).toBeVisible()
  await cellInput.fill('0.5')
  const [cellResp] = await Promise.all([
    page.waitForResponse((r) => r.url().includes('/api/schedule/rows/') && r.request().method() === 'PUT'),
    cellInput.press('Enter')
  ])
  expect(cellResp.ok()).toBeTruthy()
  await expect(cell.locator('.cell-value')).toHaveText('0.5')

  // 刷新后从服务端重新加载，验证已落库
  await page.reload()
  const reloaded = scheduleRow(page, data.requirementTitle)
  await expect(reloaded).toBeVisible()
  await expect(reloaded.locator('td.cell-day:not(.cell-locked)').first().locator('.cell-value'))
    .toHaveText('0.5')
})

test('排期：乐观锁冲突时提示并整表回滚', async ({ page }) => {
  await loginViaUi(page, ADMIN_USERNAME, ADMIN_PASSWORD)
  await page.goto('/schedule')

  const row = scheduleRow(page, data.requirementTitle)
  await expect(row).toBeVisible()

  // 页面加载后由 API 抢先更新该行，制造 UI 端的过期 updatedAt
  const view = await apiGet<ScheduleViewLike>(api,
    `/api/schedule/months/${data.monthId}/view`, data.adminToken)
  const group = view.groups.find((g) => g.requirementId === data.requirementId)
  expect(group, '当月视图应包含造数需求').toBeTruthy()
  const target = group!.rows[0]
  await apiPut(api, `/api/schedule/rows/${target.rowId}`, data.adminToken, {
    plannedEffort: target.plannedEffort ?? 1,
    cells: {},
    updatedAt: target.updatedAt
  })

  const cell = row.locator('td.cell-day:not(.cell-locked)').first()
  await cell.locator('.cell-value').click()
  const cellInput = cell.locator('input.cell-input')
  await expect(cellInput).toBeVisible()
  await cellInput.fill('0.3')
  await cellInput.press('Enter')

  // 后端返回 code=409 → 拦截器提示；前端随后整表刷新丢弃本地修改
  await expect(page.getByText('数据已被他人修改，请刷新后重试')).toBeVisible()
  await expect(cell.locator('.cell-value')).not.toHaveText('0.3')
})

test('需求详情：PM 取消需求后进入终态', async ({ page }) => {
  await loginViaUi(page, ADMIN_USERNAME, ADMIN_PASSWORD)
  await page.goto(`/requirements/${data.pickableRequirementId}`)

  await page.getByRole('button', { name: '取消需求' }).click()
  await page.getByPlaceholder('请填写取消原因（必填）').fill('E2E 自动化取消')
  await page.getByRole('button', { name: '确认取消' }).click()

  await expect(page.getByText('需求已取消')).toBeVisible()
  await expect(page.getByRole('button', { name: '取消需求' })).toHaveCount(0)

  const detail = await apiGet<{ status: string }>(api,
    `/api/requirements/${data.pickableRequirementId}`, data.adminToken)
  expect(detail.status).toBe('CANCELLED')
})

test('Bug：逐格编辑功能字段并持久化', async ({ page }) => {
  const bugNo = `BUG-${data.projectCode}-0001`
  await loginViaUi(page, ADMIN_USERNAME, ADMIN_PASSWORD)
  await page.goto('/bugs')

  await page.locator('.toolbar-project').click()
  await page.locator('.el-select-dropdown__item:visible')
    .filter({ hasText: data.projectName }).click()
  await page.getByRole('button', { name: '添加Bug' }).click()
  await expect(page.getByText(bugNo).first()).toBeVisible()

  // 可编辑文本格顺序：版本(0) / 功能(1) / 描述(2) / 处理方案(3)
  const row = page.locator('.vxe-body--row').filter({ hasText: bugNo }).first()
  await row.locator('.cell-text.editable').nth(1).click()
  // 进入编辑后该 td 的 .cell-text 被 input 替换，故直接取行内唯一编辑框
  const input = row.locator('input.cell-input')
  await expect(input).toBeVisible()
  await input.fill('E2E功能字段')
  const [resp] = await Promise.all([
    page.waitForResponse((r) => r.url().includes('/api/bugs/') && r.request().method() === 'PUT'),
    input.press('Enter')
  ])
  expect(resp.ok()).toBeTruthy()

  // 刷新后重新选中记忆的项目，验证格子值已持久化（功能列为主表第 4 列）
  await page.reload()
  await expect(page.getByText(bugNo).first()).toBeVisible()
  const reloadedRow = page.locator('.vxe-body--row').filter({ hasText: bugNo }).first()
  await expect(reloadedRow.locator('td').nth(3)).toHaveText('E2E功能字段')
})

test('人员管理：新增人员后可用默认密码登录并被要求改密', async ({ page }) => {
  const username = `ui${Date.now().toString(36)}`
  await loginViaUi(page, ADMIN_USERNAME, ADMIN_PASSWORD)
  await page.goto('/admin/users')

  await page.getByRole('button', { name: '新增人员' }).click()
  const dialog = page.locator('.el-dialog').filter({ hasText: '新增人员' })
  await expect(dialog).toBeVisible()
  await dialog.getByPlaceholder('3-30 位字母或数字').fill(username)
  await dialog.getByPlaceholder('请输入姓名').fill('E2E新成员')
  await dialog.getByRole('button', { name: '确定' }).click()
  await expect(page.getByText('新增成功')).toBeVisible()

  // 清空本页登录态后以新账号登录：初始密码为默认密码，首次登录强制改密
  await page.evaluate(() => localStorage.clear())
  await loginViaUi(page, username, ADMIN_PASSWORD)
  await expect(page).toHaveURL(/\/change-password/)
})

test('日历管理：工作日改为节假日后排期对应列灰显', async ({ page }) => {
  const now = new Date()
  const calendar = await apiGet<{ days: Array<{ day: string; type: string }> }>(api,
    `/api/calendar?year=${now.getFullYear()}&month=${now.getMonth() + 1}`, data.adminToken)
  const workday = calendar.days.find((d) => d.type === 'WORKDAY')
  expect(workday, '当月应存在工作日').toBeTruthy()
  const day = workday!.day
  const dayNum = String(Number(day.slice(8, 10)))

  try {
    await loginViaUi(page, ADMIN_USERNAME, ADMIN_PASSWORD)
    await page.goto('/admin/calendar')

    await page.locator('.day-cell.type-workday').first().click()
    const dialog = page.locator('.el-dialog').filter({ hasText: '编辑日历' })
    await expect(dialog).toBeVisible()
    await dialog.locator('.el-radio').filter({ hasText: '节假日' }).click()
    await dialog.getByPlaceholder('如：国庆节').fill('E2E节假日')
    await dialog.getByRole('button', { name: '保存' }).click()
    await expect(page.getByText('日历已更新')).toBeVisible()

    // 排期页该日列头应变为不可排（灰显）
    await page.goto('/schedule')
    await expect(scheduleRow(page, data.requirementTitle)).toBeVisible()
    const headerCell = page.locator('th.vxe-header--column')
      .filter({ has: page.getByText(dayNum, { exact: true }) }).first()
    await expect(headerCell.locator('.day-head')).toHaveClass(/day-off/)
  } finally {
    // 还原为工作日，避免影响同一环境中的其他用例
    await apiPut(api, `/api/calendar/${day}`, data.adminToken, { type: 'WORKDAY' })
  }
})
