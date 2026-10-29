import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

export default defineConfig({
  plugins: [react()],
  // The browser only talks to the PantryChef backend; in development /api is forwarded to it.
  server: {
    proxy: { '/api': process.env.PANTRYCHEF_BACKEND_URL || 'http://localhost:8080' },
  },
  test: {
    environment: 'jsdom',
    setupFiles: ['./src/test-setup.js'],
  },
})
