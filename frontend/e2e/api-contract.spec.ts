import { expect, request as apiRequest, test, type APIRequestContext } from '@playwright/test'
import { readFileSync } from 'node:fs'
import { fileURLToPath } from 'node:url'
import {
  ADMIN_PASSWORD,
  ADMIN_USERNAME,
  seedCoreFlow,
  type SeededFlow
} from './helpers'
import { validateAgainstSchema, type OpenApiDoc, type OpenApiSchema } from './contract-validator'

/**
 * E2E 契约校验：用真实后端响应校验 contracts/openapi.json 的关键响应结构。
 *
 * 与前端静态契约测试（src/api/contract.spec.ts）互补：
 * · 前端侧保证"调用的路径/方法"都在契约中；
 * · 这里保证"真实返回的数据结构"符合契约，防止字段改名/类型漂移。
 */
test.skip(!process.env.E2E_BASE_URL, '未设置 E2E_BASE_URL，跳过 E2E')
test.describe.configure({ mode: 'serial' })

const contract = JSON.parse(
  readFileSync(fileURLToPath(new URL('../../contracts/openapi.json', import.meta.url)), 'utf8')
) as OpenApiDoc

let api: APIRequestContext
let data: SeededFlow

test.beforeAll(async () => {
  api = await apiRequest.newContext({ baseURL: process.env.E2E_BASE_URL })
  data = await seedCoreFlow(api)
})

test.afterAll(async () => {
  await api.dispose()
})

interface ValidationTarget {
  method: 'get' | 'post'
  /** 实际请求路径（可带具体 id / query） */
  path: string
  /** 契约中的路径模板 */
  contractPath: string
  token?: string
  payload?: unknown
}

async function requestAndValidate(target: ValidationTarget): Promise<Record<string, unknown>> {
  const headers = target.token ? { Authorization: `Bearer ${target.token}` } : {}
  const response = target.method === 'get'
    ? await api.get(target.path, { headers })
    : await api.post(target.path, { headers, data: target.payload })
  expect(response.ok()).toBeTruthy()

  const body = await response.json()
  expect(body.code, `${target.method.toUpperCase()} ${target.path} 业务码`).toBe(200)

  const operation = contract.paths[target.contractPath]?.[target.method]
  expect(operation, `契约缺少 ${target.method.toUpperCase()} ${target.contractPath}`).toBeTruthy()
  const schema = operation.responses?.['200']?.content?.['application/json']?.schema as OpenApiSchema
  const errors = validateAgainstSchema(body, schema, contract)
  expect(errors, `${target.method.toUpperCase()} ${target.contractPath} 响应不符合契约：\n${errors.join('\n')}`)
    .toEqual([])

  return body as Record<string, unknown>
}

test('契约：登录响应结构', async () => {
  await requestAndValidate({
    method: 'post',
    path: '/api/auth/login',
    contractPath: '/api/auth/login',
    payload: { username: ADMIN_USERNAME, password: ADMIN_PASSWORD }
  })
})

test('契约：工作台汇总结构', async () => {
  await requestAndValidate({
    method: 'get',
    path: '/api/dashboard/summary',
    contractPath: '/api/dashboard/summary',
    token: data.adminToken
  })
})

test('契约：项目分页结构', async () => {
  await requestAndValidate({
    method: 'get',
    path: '/api/projects?page=1&size=1',
    contractPath: '/api/projects',
    token: data.adminToken
  })
})

test('契约：需求分页结构', async () => {
  await requestAndValidate({
    method: 'get',
    path: '/api/requirements?page=1&size=1',
    contractPath: '/api/requirements',
    token: data.adminToken
  })
})

test('契约：排期月表与视图结构', async () => {
  await requestAndValidate({
    method: 'get',
    path: '/api/schedule/months',
    contractPath: '/api/schedule/months',
    token: data.adminToken
  })
  await requestAndValidate({
    method: 'get',
    path: `/api/schedule/months/${data.monthId}/view`,
    contractPath: '/api/schedule/months/{monthId}/view',
    token: data.adminToken
  })
})

test('契约：Bug 分页结构', async () => {
  await requestAndValidate({
    method: 'get',
    path: `/api/bugs?projectId=${data.projectId}&page=1&size=1`,
    contractPath: '/api/bugs',
    token: data.adminToken
  })
})
