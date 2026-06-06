import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'
import { fileURLToPath, URL } from 'node:url'

export default defineConfig({
  plugins: [react()],
  build: {
    // Generate source maps for error tracking, but WITHOUT a sourceMappingURL
    // comment in the bundle. The .map files are emitted to dist/ for upload to
    // an error tracker (e.g. Sentry); they are intentionally NOT staged into
    // the server's public static dir, so original source stays unexposed.
    sourcemap: 'hidden',
  },
  resolve: {
    alias: {
      '@': fileURLToPath(new URL('./src', import.meta.url)),
    },
  },
  server: {
    proxy: {
      '/api': {
        target: 'https://localhost:8443',
        changeOrigin: true,
        secure: false,       // accept the self-signed dev cert
      },
      '/oauth2': {
        target: 'https://localhost:8443',
        changeOrigin: true,
        secure: false,
      },
      '/login': {
        target: 'https://localhost:8443',
        changeOrigin: true,
        secure: false,
      },
    },
  },
})
