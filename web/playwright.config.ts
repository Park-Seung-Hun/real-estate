import { defineConfig } from '@playwright/test'

export default defineConfig({
  testDir: 'tests/e2e',
  // 기본 test-results는 실행 시작 때 비워져 스크립트가 남기는 앱 로그(app.log)가 지워진다
  outputDir: 'test-results/playwright',
  use: { viewport: { width: 360, height: 740 }, baseURL: process.env.E2E_BASE_URL ?? 'http://localhost:8080' },
})
