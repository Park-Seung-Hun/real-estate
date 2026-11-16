import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

const api = 'http://localhost:8080'

export default defineConfig({
  plugins: [react()],
  server: {
    proxy: {
      '/api': api,
      '/oauth2': api,
      '/login/oauth2': api,
    },
  },
})
