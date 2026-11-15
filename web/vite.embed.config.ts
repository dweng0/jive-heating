import path from 'node:path'
import react from '@vitejs/plugin-react'
import { defineConfig } from 'vite'

// The Live view for the Android app: `npm run build:live-embed` writes it into the app's assets, served there by
// WebViewAssetLoader. Relative paths, so it works from any folder.
export default defineConfig({
  plugins: [react()],
  base: './',
  publicDir: false,
  resolve: { alias: { '@': path.resolve(__dirname, './src') } },
  build: {
    outDir: path.resolve(__dirname, '../android/app/src/main/assets/live'),
    emptyOutDir: true,
    rollupOptions: { input: path.resolve(__dirname, 'live-embed.html') },
    chunkSizeWarningLimit: 1500,
  },
})
