import {defineConfig, loadEnv} from 'vite'
import react from '@vitejs/plugin-react'
import wasm from 'vite-plugin-wasm'
import topLevelAwait from 'vite-plugin-top-level-await'

// https://vitejs.dev/config/
export default defineConfig(({ mode }) => {
  const env = loadEnv(mode, process.cwd(), '')
  const apiProxyTarget = env.VITE_API_PROXY_TARGET || 'http://localhost:8080'

  return {
    plugins: [
      wasm(),
      topLevelAwait(),
      react(),
    ],
    build: {
      rollupOptions: {
        output: {
          manualChunks: {
            'react-vendor': ['react', 'react-dom', 'react-router-dom'],
            'ui-vendor': ['framer-motion', 'lucide-react'],
            // 高亮器已改为 prism-light 按需加载，独立成块以便浏览器按需缓存
            'syntax-highlighter': ['react-syntax-highlighter'],
            // 图表与 Markdown 渲染体积较大，单独成块避免页面 chunk 超过 500 kB
            'charts-vendor': ['recharts'],
            'markdown-vendor': ['react-markdown', 'remark-gfm'],
          },
        },
      },
    },
    server: {
      host: '0.0.0.0',
      port: 5173,
      proxy: {
        '/api': {
          target: apiProxyTarget,
          changeOrigin: true,
        },
        '/ws': {
          target: apiProxyTarget,
          changeOrigin: true,
          ws: true,
        },
      },
    },
  }
});
