#!/usr/bin/env node
/**
 * 聚合四层测试产物，生成一份 Markdown 质量摘要。
 *
 * 用法：
 *   node scripts/test-summary.mjs                     # 扫描仓库当前产物
 *   node scripts/test-summary.mjs artifacts           # 扫描 CI 下载的 artifacts 目录
 *   node scripts/test-summary.mjs artifacts out.md
 *
 * 数据来源：
 *   · Surefire / Failsafe XML（后端单元、集成）
 *   · Vitest JUnit XML（前端单元）
 *   · Playwright JSON reporter（E2E，含 flaky 统计）
 *   · k6 --summary-export JSON（性能冒烟）
 */
import { existsSync, mkdirSync, readdirSync, readFileSync, statSync, writeFileSync } from 'node:fs'
import { dirname, join } from 'node:path'

const root = process.argv[2] || '.'
const outFile = process.argv[3] || 'test-summary.md'

function walk(dir, files = []) {
  if (!existsSync(dir)) return files
  for (const name of readdirSync(dir)) {
    const full = join(dir, name)
    if (statSync(full).isDirectory()) walk(full, files)
    else files.push(full)
  }
  return files
}

function attr(tag, name) {
  const match = tag.match(new RegExp(`${name}="(\\d+)"`))
  return match ? Number(match[1]) : 0
}

function parseJUnit(fileList) {
  const total = { tests: 0, failures: 0, errors: 0, skipped: 0 }
  for (const file of fileList) {
    const xml = readFileSync(file, 'utf8')
    for (const match of xml.matchAll(/<testsuite\b[^>]*>/g)) {
      const tag = match[0]
      total.tests += attr(tag, 'tests')
      total.failures += attr(tag, 'failures')
      total.errors += attr(tag, 'errors')
      total.skipped += attr(tag, 'skipped')
    }
  }
  return total
}

function readJson(file) {
  try {
    return JSON.parse(readFileSync(file, 'utf8'))
  } catch {
    return null
  }
}

const files = walk(root)
const xmlFiles = files.filter((file) => file.endsWith('.xml'))
const backendUnit = parseJUnit(xmlFiles.filter((file) => file.includes('surefire-reports')))
const backendIT = parseJUnit(xmlFiles.filter((file) => file.includes('failsafe-reports')))
const frontendUnit = parseJUnit(xmlFiles.filter((file) => file.includes('unit-junit')))

const e2eFile = files.find((file) => file.endsWith('e2e-results.json'))
const e2e = e2eFile ? (readJson(e2eFile)?.stats ?? null) : null

const k6File = files.find((file) => file.endsWith('k6-summary.json'))
const k6 = k6File ? readJson(k6File) : null
// k6 --summary-export 的指标值直接挂在 metric 下（旧版/部分版本在 values 下），两者兼容
const k6Checks = k6?.metrics?.checks?.values ?? k6?.metrics?.checks
const k6Duration = k6?.metrics?.http_req_duration?.values ?? k6?.metrics?.http_req_duration
const k6FailedMetric = k6?.metrics?.http_req_failed?.values ?? k6?.metrics?.http_req_failed

function passed(stat) {
  return stat.tests - stat.failures - stat.errors - stat.skipped
}

function junitRow(label, stat, note) {
  return `| ${label} | ${stat.tests} | ${passed(stat)} | ${stat.failures + stat.errors} | ${stat.skipped} | ${note} |`
}

const rows = []
if (backendUnit.tests > 0) rows.push(junitRow('后端单元 / 切片 / 架构', backendUnit, 'Surefire'))
if (backendIT.tests > 0) rows.push(junitRow('后端集成', backendIT, 'Failsafe + Testcontainers'))
if (frontendUnit.tests > 0) rows.push(junitRow('前端单元 / 契约 / 组件', frontendUnit, 'Vitest'))

let e2eTotal = 0
let e2ePassed = 0
let e2eFailed = 0
let e2eSkipped = 0
if (e2e) {
  e2ePassed = (e2e.expected ?? 0) + (e2e.flaky ?? 0)
  e2eFailed = e2e.unexpected ?? 0
  e2eSkipped = e2e.skipped ?? 0
  e2eTotal = e2ePassed + e2eFailed + e2eSkipped
  rows.push(`| 前端 E2E | ${e2eTotal} | ${e2ePassed} | ${e2eFailed} | ${e2eSkipped} | Playwright，flaky ${e2e.flaky ?? 0} |`)
}

const total = backendUnit.tests + backendIT.tests + frontendUnit.tests + e2eTotal
const totalPassed = passed(backendUnit) + passed(backendIT) + passed(frontendUnit) + e2ePassed
const totalFailed =
  backendUnit.failures + backendUnit.errors +
  backendIT.failures + backendIT.errors +
  frontendUnit.failures + frontendUnit.errors +
  e2eFailed
const totalSkipped = backendUnit.skipped + backendIT.skipped + frontendUnit.skipped + e2eSkipped

const lines = [
  '# 测试质量摘要',
  '',
  `> 由 \`scripts/test-summary.mjs\` 自动生成${e2eFile ? '' : '（未发现 E2E 报告）'}${k6 ? '' : '（未发现 k6 报告）'}`,
  '',
  '| 层级 | 用例 | 通过 | 失败 | 跳过 | 说明 |',
  '|---|---:|---:|---:|---:|---|',
  ...rows,
  `| **合计** | **${total}** | **${totalPassed}** | **${totalFailed}** | **${totalSkipped}** | 四层用例 |`
]

if (k6Checks) {
  const p95 = k6Duration?.['p(95)']
  const failedRate = k6FailedMetric?.rate ?? k6FailedMetric?.value
  lines.push(
    '',
    '## 性能冒烟（k6）',
    '',
    `- checks：**${k6Checks.passes} 通过 / ${k6Checks.fails} 失败**`,
    `- HTTP p95：**${typeof p95 === 'number' ? p95.toFixed(2) : '-'} ms**`,
    `- HTTP 失败率：**${typeof failedRate === 'number' ? (failedRate * 100).toFixed(2) : '-'}%**`
  )
}

lines.push(
  '',
  '## 门禁',
  '',
  '- 后端：JaCoCo 行覆盖 ≥ 80%，关键类单独阈值；`mvn verify` 含 Testcontainers 集成测试',
  '- 前端：行/语句 ≥ 90%、分支 ≥ 90%、函数 ≥ 80%',
  '- 契约：Controller / 前端调用 / 真实响应三层对齐 `contracts/openapi.json`',
  '- 部署：`build-deploy` 通过 `workflow_call` 复用测试工作流，测试失败不构建、不部署',
  ''
)

const markdown = lines.join('\n')
const outDir = dirname(outFile)
if (outDir !== '.') mkdirSync(outDir, { recursive: true })
writeFileSync(outFile, markdown, 'utf8')
console.log(markdown)
