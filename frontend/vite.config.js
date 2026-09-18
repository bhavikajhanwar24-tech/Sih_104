import { defineConfig } from 'vite';
import react from '@vitejs/plugin-react';
import path from 'node:path';
import { fileURLToPath } from 'node:url';

const __dirname = path.dirname(fileURLToPath(import.meta.url));

export default defineConfig({
  plugins: [react()],
  define: {
    // sockjs-client expects `global`
    global: 'globalThis',
  },
  resolve: {
    alias: {
      '@': path.resolve(__dirname, 'src'),
    },
  },
  build: {
    // AudioWorklet addModule() is unreliable with data: URLs in some browsers —
    // always emit capture-worklet.js as a real asset.
    assetsInlineLimit: (filePath) => {
      if (String(filePath).includes('capture-worklet')) return false;
      return undefined; // fall back to default 4096
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
        target: 'http://localhost:8080',
        changeOrigin: true,
        ws: true,
      },
      // Narrow prefix — MUST NOT be '/ws' or it can steal /ws-sentinel traffic.
      '/ws/ingest': {
        target: 'http://localhost:8000',
        changeOrigin: true,
        ws: true,
        rewrite: (p) => p.replace(/^\/ws/, ''),
      },
      // ml-engine REST (health / diagnostics) — keep separate from /api (Java).
      '/engine': {
        target: 'http://localhost:8000',
        changeOrigin: true,
        rewrite: (p) => p.replace(/^\/engine/, ''),
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
  },
});
