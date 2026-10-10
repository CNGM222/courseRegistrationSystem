import { defineConfig } from 'vite'
import vue from '@vitejs/plugin-vue'

export default defineConfig({
  plugins: [vue()],
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://49.140.70.154:8080',
        changeOrigin: true,
        secure: false,
      },
    },
  },
})
