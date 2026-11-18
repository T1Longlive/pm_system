/**
 * k6 性能冒烟：登录后打 3 个核心读接口 + 当前月视图。
 *
 * 目标不是压测，而是用阈值把"明显变慢/开始报错"挡在部署前：
 *   p95 < 800ms、失败率 < 1%、业务码全为 200。
 *
 * 运行方式（需先启动后端）：
 *   BASE_URL=http://localhost:18080 k6 run perf/smoke.js
 * 或由 run-local.mjs 在 E2E 环境未销毁前自动执行（PERF_SMOKE=1）。
 */
import http from 'k6/http'
import { check, fail, sleep } from 'k6'

const BASE_URL = __ENV.BASE_URL || 'http://localhost:18080'
const USERNAME = __ENV.K6_USERNAME || 'admin'
const PASSWORD = __ENV.K6_PASSWORD || 'Pm@123456'

export const options = {
  vus: 2,
  duration: '20s',
  thresholds: {
    // 后端为单机 + 测试库，阈值留出 CI 冷启动余量；超过即视为性能回归
    http_req_failed: ['rate<0.01'],
    http_req_duration: ['p(95)<800'],
    checks: ['rate>0.99']
  }
}

export function setup() {
  const login = http.post(
    `${BASE_URL}/api/auth/login`,
    JSON.stringify({ username: USERNAME, password: PASSWORD }),
    {
      headers: { 'Content-Type': 'application/json' },
      tags: { name: 'login' }
    }
  )
  if (login.status !== 200 || login.json('code') !== 200) {
    fail(`登录失败：HTTP ${login.status} / body ${login.body}`)
  }
  const token = login.json('data.token')
  const headers = { Authorization: `Bearer ${token}` }
  const months = http.get(`${BASE_URL}/api/schedule/months`, {
    headers,
    tags: { name: 'schedule-months' }
  })
  const monthId = months.json('data.0.id')
  return { token, monthId }
}

export default function (data) {
  const headers = { Authorization: `Bearer ${data.token}` }

  const responses = http.batch([
    ['GET', `${BASE_URL}/api/dashboard/summary`, null,
      { headers, tags: { name: 'dashboard-summary' } }],
    ['GET', `${BASE_URL}/api/requirements?page=1&size=10`, null,
      { headers, tags: { name: 'requirements-list' } }],
    ['GET', `${BASE_URL}/api/schedule/months`, null,
      { headers, tags: { name: 'schedule-months' } }]
  ])

  for (const response of responses) {
    check(response, {
      'HTTP 200': (r) => r.status === 200,
      '业务码 200': (r) => r.json('code') === 200
    })
  }

  if (data.monthId) {
    const view = http.get(`${BASE_URL}/api/schedule/months/${data.monthId}/view`, {
      headers,
      tags: { name: 'schedule-view' }
    })
    check(view, {
      '视图 200': (r) => r.status === 200,
      '视图业务码 200': (r) => r.json('code') === 200
    })
  }

  sleep(1)
}
