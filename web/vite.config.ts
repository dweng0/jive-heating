import path from 'node:path'
import tailwindcss from '@tailwindcss/vite'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// Dev: `npm run dev` proxies the API to a local service (or an ssh tunnel to your home server on :8098).
const api = process.env.HEATING_API ?? 'http://localhost:8098'

export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: { alias: { '@': path.resolve(__dirname, './src') } },
  server: {
    proxy: {
      '/api': { target: api, changeOrigin: false },
      '/ws': { target: api, ws: true, changeOrigin: false },
    },
  },
})
