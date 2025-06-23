import {defineConfig, loadEnv} from 'vite';           // импортируем loadEnv для работы с .env[1]
import react from '@vitejs/plugin-react-swc';
import topLevelAwait from 'vite-plugin-top-level-await';

export default defineConfig(({mode}) => {

  const env = loadEnv(mode, process.cwd());

  return {
    plugins: [react(), topLevelAwait()],
    server: {
      port: 3000,
      proxy: {
        '/events': {
          target: env.VITE_SERVER_BASE_URL,                               // используем VITE_SERVER_BASE_URL как proxy target[2]
          changeOrigin: true,
          ws: true
        }
      }
    },
    build: {
      outDir: 'dist',
      sourcemap: true
    },
    resolve: {
      alias: {
        '@novnc/novnc/core/rfb': '@novnc/novnc/lib/rfb.js'   // <-- ключевая строка
      }
    },
    optimizeDeps: {
      exclude: ['@novnc/novnc/lib/rfb.js']   // ⬅ не трогать rfb.js
    }
  };
});
