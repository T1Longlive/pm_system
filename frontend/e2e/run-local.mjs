#!/usr/bin/env node
/**
 * E2E 本地编排脚本：一条命令拉起完整环境并跑 Playwright。
 *
 *   依赖容器（MySQL 13306 / Redis 16379）
 *     → 后端 spring-boot:run（8080，测试库）
 *     → 前端 vite dev（5173，/api 代理到 8080）
 *     → playwright test
 *     → 清理进程与容器
 *
 * 用法（frontend 目录）：
 *   npm run test:e2e:local
 *
 * 前置：Docker Desktop 已启动；仓库内存在 .tools/apache-maven-3.9.16，
 * 否则回退到 PATH 里的 mvn。只想连已有环境时用：
 *   E2E_BASE_URL=http://host:8081 npx playwright test
 */
import { spawn, spawnSync } from 'node:child_process'
import { existsSync, mkdirSync } from 'node:fs'
import { setTimeout as sleep } from 'node:timers/promises'
import { fileURLToPath } from 'node:url'
import os from 'node:os'
import path from 'node:path'
import process from 'node:process'

const isWin = process.platform === 'win32'
const frontendDir = path.dirname(path.dirname(fileURLToPath(import.meta.url)))
const rootDir = path.dirname(frontendDir)
const composeFile = path.join(rootDir, 'docker-compose.e2e.yml')

const baseUrl = process.env.E2E_BASE_URL || 'http://localhost:5173'
const backendPort = '18080'
const backendOrigin = `http://localhost:${backendPort}`
const backendHealthUrl = `${backendOrigin}/api/auth/me`

const children = []

function execBlocking(command, args, options = {}) {
  const result = spawnSync(command, args, { stdio: 'inherit', shell: isWin, ...options })
  if (result.status !== 0) {
    throw new Error(`${command} ${args.join(' ')} 失败（exit ${result.status}）`)
  }
}

function start(command, args, options = {}) {
  const child = spawn(command, args, {
    stdio: ['ignore', 'pipe', 'pipe'],
    shell: isWin,
    ...options
  })
  const forward = (chunk) => {
    if (process.env.E2E_VERBOSE) process.stdout.write(chunk)
  }
  child.stdout.on('data', forward)
  child.stderr.on('data', forward)
  children.push(child)
  return child
}

async function waitFor(url, name, timeoutMs) {
  const startAt = Date.now()
  while (Date.now() - startAt < timeoutMs) {
    try {
      const response = await fetch(url)
      // 后端未登录时返回 HTTP 200 + body code=401，同样说明服务已就绪
      if (response.status < 500) return
    } catch {
      // 连接被拒绝：继续等
    }
    await sleep(1000)
  }
  throw new Error(`${name} 在 ${timeoutMs}ms 内未就绪：${url}`)
}

async function assertPortFree(url, name) {
  try {
    await fetch(url)
  } catch {
    return
  }
  throw new Error(`${name} 端口已被占用，请先停止本机已有实例：${url}`)
}

function stopAll() {
  for (const child of children) {
    if (child.exitCode !== null) continue
    if (isWin) {
      spawnSync('taskkill', ['/pid', String(child.pid), '/T', '/F'], { stdio: 'ignore' })
    } else {
      child.kill('SIGTERM')
    }
  }
}

function resolveMaven() {
  const bundled = path.join(rootDir, '.tools', 'apache-maven-3.9.16', 'bin', isWin ? 'mvn.cmd' : 'mvn')
  return existsSync(bundled) ? bundled : 'mvn'
}

/**
 * 性能冒烟：优先用本机 k6；未安装时回退到 grafana/k6 容器（Docker 已是 E2E 前置）。
 * 容器内通过 host.docker.internal 访问宿主机的 E2E 后端。
 */
function runPerfSmoke() {
  const native = spawnSync('k6', ['version'], { stdio: 'ignore', shell: isWin })
  if (!native.error && native.status === 0) {
    return spawnSync('k6', [
      'run', '--summary-export', 'perf/k6-summary.json', 'perf/smoke.js'
    ], {
      stdio: 'inherit',
      shell: isWin,
      cwd: frontendDir,
      env: { ...process.env, BASE_URL: backendOrigin }
    })
  }
  console.log('[e2e] 本机未安装 k6，回退到 grafana/k6 容器…')
  return spawnSync('docker', [
    'run', '--rm',
    '-e', `BASE_URL=http://host.docker.internal:${backendPort}`,
    '-v', `${path.join(frontendDir, 'perf')}:/perf`,
    'grafana/k6', 'run', '--summary-export', '/perf/k6-summary.json', '/perf/smoke.js'
  ], { stdio: 'inherit', shell: isWin })
}

const backendEnv = {
  ...process.env,
  SERVER_PORT: backendPort,
  DB_URL: 'jdbc:mysql://localhost:13306/pm_system?useUnicode=true&characterEncoding=utf8'
    + '&serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true',
  DB_USERNAME: 'root',
  DB_PASSWORD: 'pm123456',
  REDIS_HOST: 'localhost',
  REDIS_PORT: '16379',
  REDIS_PASSWORD: 'pm123456',
  PM_UPLOAD_DIR: path.join(os.tmpdir(), 'pm-e2e-upload'),
  SQL_LOG_IMPL: 'org.apache.ibatis.logging.nologging.NoLoggingImpl'
}

let exitCode = 1
try {
  await assertPortFree(baseUrl, '前端')
  await assertPortFree(backendHealthUrl, '后端')

  console.log('[e2e] 启动依赖容器（MySQL / Redis）…')
  execBlocking('docker', ['compose', '-f', composeFile, 'up', '-d', '--wait'], { cwd: rootDir })

  console.log('[e2e] 启动后端（spring-boot:run）…')
  start(resolveMaven(), ['-s', path.join(rootDir, 'settings.xml'),
    '-f', path.join(rootDir, 'backend', 'pom.xml'), 'spring-boot:run'], {
    cwd: rootDir,
    env: backendEnv
  })
  await waitFor(backendHealthUrl, '后端', 240_000)

  console.log('[e2e] 启动前端（vite dev）…')
  start(isWin ? 'npm.cmd' : 'npm', ['run', 'dev', '--', '--port', '5173', '--strictPort'], {
    cwd: frontendDir,
    env: {
      ...process.env,
      BROWSER: 'none',
      // 让 vite 的 /api 代理指向本次 E2E 后端端口
      VITE_API_TARGET: backendOrigin
    }
  })
  await waitFor(`${baseUrl}/login`, '前端', 90_000)

  mkdirSync(path.join(frontendDir, 'reports'), { recursive: true })
  console.log(`[e2e] 运行 Playwright（baseURL=${baseUrl}）…`)
  const playwright = spawnSync(isWin ? 'npx.cmd' : 'npx', ['playwright', 'test'], {
    stdio: 'inherit',
    shell: isWin,
    cwd: frontendDir,
    env: { ...process.env, E2E_BASE_URL: baseUrl }
  })
  exitCode = playwright.status ?? 1

  // 性能冒烟：复用尚未销毁的 E2E 环境。CI 安装 k6 后通过 PERF_SMOKE=1 开启。
  if (exitCode === 0 && process.env.PERF_SMOKE === '1') {
    console.log('[e2e] 运行 k6 性能冒烟…')
    const perf = runPerfSmoke()
    if (perf.error) {
      console.error('[e2e] k6（本机与 Docker）均不可用；安装 k6 或启动 Docker 后重试')
      exitCode = 1
    } else {
      exitCode = perf.status ?? 1
    }
  }
} catch (error) {
  console.error(`[e2e] ${error instanceof Error ? error.message : error}`)
} finally {
  console.log('[e2e] 清理进程与容器…')
  stopAll()
  try {
    execBlocking('docker', ['compose', '-f', composeFile, 'down', '-v'], { cwd: rootDir })
  } catch {
    console.warn('[e2e] 容器清理失败，可手工执行：docker compose -f docker-compose.e2e.yml down -v')
  }
}

process.exit(exitCode)
