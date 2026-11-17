import { fileURLToPath, URL } from 'node:url'
import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

// https://vitejs.dev/config/
// Vite 配置：@ 别名指向 src；开发环境将 /api 与 /upload（附件静态资源）代理到后端
// 后端地址可用 VITE_API_TARGET 覆盖（E2E 本地编排用非默认端口，避免与本机其他服务冲突）
const apiTarget = process.env.VITE_API_TARGET || 'http://localhost:8080'

export default defineConfig({
  plugins: [vue()],
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url))
    }
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: apiTarget,
        changeOrigin: true
      },
      '/upload': {
        target: apiTarget,
        changeOrigin: true
      }
    }
  }
})
