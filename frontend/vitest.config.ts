import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vitest/config'
import vue from '@vitejs/plugin-vue'

// Vitest 配置：与 vite.config.ts 保持同样的别名，但独立一份，避免测试环境混入 dev server 配置
export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  test: {
    environment: 'jsdom',
    environmentOptions: {
      jsdom: {
        // 非 opaque origin 才有可用的 localStorage
        url: 'http://localhost'
      }
    },
    setupFiles: ['./src/test/setup.ts'],
    include: ['src/**/*.spec.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'lcov'],
      // 首批只对「纯逻辑 + 会话 + 请求封装」设门槛；组件覆盖率随用例补齐后再纳入
      include: [
        'src/utils/**/*.ts',
        'src/constants/**/*.ts',
        'src/stores/**/*.ts',
        'src/api/request.ts'
      ],
      thresholds: {
        lines: 80,
        functions: 80,
        statements: 80,
        branches: 70
      }
    }
  }
})
