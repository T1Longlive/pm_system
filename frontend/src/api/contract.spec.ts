import { describe, expect, it } from 'vitest'
import contract from '../../../contracts/openapi.json'

/**
 * 前后端接口契约的前端侧校验：
 * 扫描 src/api 下所有 request 调用，与 contracts/openapi.json 的路径/方法双向比对。
 *
 * 目的：前端新增/修改接口而忘记同步契约，或后端改路径导致契约过期，都会在这里失败。
 * 不发起任何 HTTP 请求，属于纯静态契约测试。
 */

const HTTP_METHODS = new Set(['get', 'post', 'put', 'delete', 'patch'])

/** 归一化路径：模板变量 ${id} 与契约里的 {id} 都折叠为 {}，去掉重复斜杠与尾部斜杠 */
function normalizePath(path: string): string {
  const normalized = path
    .replace(/\$\{[^}]+\}/g, '{}')
    .replace(/\{[^}]*\}/g, '{}')
    .replace(/\/{2,}/g, '/')
  return normalized.length > 1 && normalized.endsWith('/')
    ? normalized.slice(0, -1)
    : normalized
}

function documentedEndpoints(): Set<string> {
  const endpoints = new Set<string>()
  Object.entries(contract.paths).forEach(([path, operations]) => {
    Object.keys(operations as Record<string, unknown>).forEach((method) => {
      if (HTTP_METHODS.has(method)) {
        endpoints.add(`${method.toUpperCase()} ${normalizePath(path)}`)
      }
    })
  })
  return endpoints
}

function sourceEndpoints(): Set<string> {
  // 以 raw 文本加载 api 模块，避免 jsdom 下 import.meta.url 不是 file:// 的问题
  const modules = import.meta.glob('./*.ts', {
    query: '?raw',
    import: 'default',
    eager: true
  }) as Record<string, string>
  const source = Object.entries(modules)
    .filter(([file]) => !file.endsWith('.spec.ts') && !file.endsWith('request.ts'))
    .map(([, content]) => content)
    .join('\n')

  const endpoints = new Set<string>()
  // request.get<...>('/path', ...) / request.delete<...>(`/path/${id}`)
  // request 的 baseURL 是 /api，源码里省略前缀，这里补回以对齐契约
  const callPattern = /request\.(get|post|put|delete)[^(]*\(\s*[`'"](\/[^`'"]*)[`'"]/g
  for (const match of source.matchAll(callPattern)) {
    endpoints.add(`${match[1].toUpperCase()} /api${normalizePath(match[2])}`)
  }
  return endpoints
}

describe('API 契约（前端侧）', () => {
  it('所有前端请求都已在 OpenAPI 契约中声明', () => {
    const documented = documentedEndpoints()
    const actual = sourceEndpoints()

    expect(actual.size).toBeGreaterThan(0)
    expect([...actual].sort(), '前端调用与 contracts/openapi.json 不一致').toEqual(
      [...documented].sort()
    )
  })

  it('契约中的路径统一使用 /api 前缀', () => {
    const paths = Object.keys(contract.paths)
    expect(paths.length).toBeGreaterThan(0)
    paths.forEach((path) => {
      expect(path, `${path} 缺少 /api 前缀`).toMatch(/^\/api\//)
    })
  })
})
