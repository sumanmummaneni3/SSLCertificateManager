import { defineConfig } from 'vite'
import react from '@vitejs/plugin-react'

export default defineConfig({
  plugins: [react()],
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
