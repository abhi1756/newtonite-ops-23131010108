import { defineConfig } from 'vitest/config';
import react from '@vitejs/plugin-react';

// In development the API runs separately (port 8081); proxying /api keeps the browser on one origin,
// exactly like production where nginx does the same, so no CORS configuration is needed.
export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      '/api': process.env.API_URL ?? 'http://localhost:8081',
    },
  },
  test: {
    environment: 'jsdom',
  },
});
