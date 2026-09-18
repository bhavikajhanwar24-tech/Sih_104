import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  server: {
    port: 5173,
    proxy: {
      '/api': {
        target: 'http://localhost:8080',
        changeOrigin: true,
      },
      '/ws-sentinel': {
        target: 'ws://localhost:8080',
        ws: true,
      },
      // Browser audio → ml-engine only (never Java).
      // Client connects to /ws/ingest/{sid} → ws://localhost:8000/ingest/{sid}
      '/ws': {
        target: 'ws://localhost:8000',
        ws: true,
        rewrite: (p) => p.replace(/^\/ws/, ''),
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
  },
});
