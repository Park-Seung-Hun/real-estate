import react from '@vitejs/plugin-react'
import { defineConfig } from 'vitest/config'

export default defineConfig({
  plugins: [react()],
  test: {
    environment: 'jsdom',
    // 날짜를 Asia/Seoul로 읽는지 가르려면 실행 시간대가 서울이 아니어야 한다(개발 PC가 KST여도).
    env: { TZ: 'UTC' },
    include: ['tests/unit/**/*.test.{ts,tsx}'],
  },
})
