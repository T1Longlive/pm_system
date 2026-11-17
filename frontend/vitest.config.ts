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
      /*
       * 门禁范围：除 views/layout 外的全部前端逻辑。
       * · views/layout 强依赖 vxe-table / element-plus 与完整路由，脆弱 DOM 断言性价比低，
       *   留给 Playwright E2E；单测层只保证纯逻辑、请求契约、组件状态派生与路由守卫。
       * · router/index.ts 是路由表装配，守卫逻辑已抽到 router/guard.ts 单测覆盖，故排除。
       */
      include: [
        'src/api/**/*.ts',
        'src/components/**/*.{ts,vue}',
        'src/constants/**/*.ts',
        'src/router/**/*.ts',
        'src/stores/**/*.ts',
        'src/utils/**/*.ts'
      ],
      exclude: ['src/router/index.ts', 'src/**/*.spec.ts', 'src/test/**'],
      thresholds: {
        lines: 90,
        functions: 80,
        statements: 90,
        branches: 90
      }
    }
  }
})
