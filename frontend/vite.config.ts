import path from 'node:path'
import { defineConfig } from 'vitest/config'
import react from '@vitejs/plugin-react'
import tailwindcss from '@tailwindcss/vite'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, './src'),
    },
  },
  server: {
    proxy: {
      '/api/v1': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      // S108: SpringDoc OpenAPI JSON + Swagger UI 在 prod single-port 由 Spring Boot 直接 serve；
      // dev :5173 須補 proxy，否則 footer「API」link 等 dev 體驗 fallback 到 SPA NotFoundPage。
      '/v3/api-docs': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/swagger-ui': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
    },
  },
  test: {
    globals: true,
    environment: 'jsdom',
    setupFiles: ['./src/setupTests.ts'],
    coverage: {
      provider: 'v8',
      reporter: ['text', 'html', 'json-summary'],
      // include whitelist：鎖定「有對應 test 的 source 檔」— frontend baseline 0 tests，
      // 若 include 全 src/**，2 個新 test 對 ~25 檔 coverage 必遠低 80%（per spec §2.1 #2）。
      // 採「漸進加入 gate」模式：後續 frontend spec 加 test 時 append 到本 list；
      // threshold 對 tested files 維持 80% aggregate；untested files 不算入分母。
      include: [
        'src/components/SkillCard.tsx',
        'src/hooks/useSemanticSearch.ts',
      ],
      thresholds: {
        lines: 80,
      },
    },
  },
})
