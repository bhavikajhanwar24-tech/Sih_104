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
    // Fail loudly if 5173 is taken — never silently drift to 5174 (CORS trap).
    strictPort: true,
    host: '127.0.0.1',
      proxy: {
      '/api': {
        // Prefer 127.0.0.1 — on Windows `localhost` can resolve to ::1 and miss
        // services bound only on IPv4.
        target: 'http://127.0.0.1:8081',
        changeOrigin: true,
        timeout: 60_000,
        proxyTimeout: 60_000,
      },
      '/ws-sentinel': {
        target: 'http://127.0.0.1:8081',
        changeOrigin: true,
        ws: true,
        timeout: 60_000,
      },
      // Narrow prefix — MUST NOT be '/ws' or it can steal /ws-sentinel traffic.
      '/ws/ingest': {
        target: 'http://127.0.0.1:8000',
        changeOrigin: true,
        ws: true,
        rewrite: (p) => p.replace(/^\/ws/, ''),
      },
      // ml-engine REST (health / diagnostics) — keep separate from /api (Java).
      '/engine': {
        target: 'http://127.0.0.1:8000',
        changeOrigin: true,
        timeout: 30_000,
        proxyTimeout: 30_000,
        rewrite: (p) => p.replace(/^\/engine/, ''),
      },
    },
  },
  test: {
    environment: 'jsdom',
    globals: true,
  },
});
