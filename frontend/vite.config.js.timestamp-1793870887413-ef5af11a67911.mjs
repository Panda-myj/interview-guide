// vite.config.js
import { defineConfig, loadEnv } from "file:///E:/project/interview-guide/interview-guide/frontend/node_modules/.pnpm/vite@5.4.21_@types+node@25.5.2_lightningcss@1.30.2/node_modules/vite/dist/node/index.js";
import react from "file:///E:/project/interview-guide/interview-guide/frontend/node_modules/.pnpm/@vitejs+plugin-react@4.7.0__6e426e0acc014edb911bb908faaa5602/node_modules/@vitejs/plugin-react/dist/index.js";
import wasm from "file:///E:/project/interview-guide/interview-guide/frontend/node_modules/.pnpm/vite-plugin-wasm@3.6.0_vite_adf32cf7255a1e82371e4eec6a62f8b7/node_modules/vite-plugin-wasm/exports/import.mjs";
import topLevelAwait from "file:///E:/project/interview-guide/interview-guide/frontend/node_modules/.pnpm/vite-plugin-top-level-await_add1cbb8ee97aa0df4b953f10c94e1be/node_modules/vite-plugin-top-level-await/exports/import.mjs";
var vite_config_default = defineConfig(function(_a) {
  var mode = _a.mode;
  var env = loadEnv(mode, process.cwd(), "");
  var apiProxyTarget = env.VITE_API_PROXY_TARGET || "http://localhost:8080";
  return {
    plugins: [
      wasm(),
      topLevelAwait(),
      react()
    ],
    build: {
      rollupOptions: {
        output: {
          manualChunks: {
            "react-vendor": ["react", "react-dom", "react-router-dom"],
            "ui-vendor": ["framer-motion", "lucide-react"],
            // 高亮器已改为 prism-light 按需加载，独立成块以便浏览器按需缓存
            "syntax-highlighter": ["react-syntax-highlighter"],
            // 图表与 Markdown 渲染体积较大，单独成块避免页面 chunk 超过 500 kB
            "charts-vendor": ["recharts"],
            "markdown-vendor": ["react-markdown", "remark-gfm"]
          }
        }
      }
    },
    server: {
      host: "0.0.0.0",
      port: 5173,
      proxy: {
        "/api": {
          target: apiProxyTarget,
          changeOrigin: true
        },
        "/ws": {
          target: apiProxyTarget,
          changeOrigin: true,
          ws: true
        }
      }
    }
  };
});
export {
  vite_config_default as default
};
//# sourceMappingURL=data:application/json;base64,ewogICJ2ZXJzaW9uIjogMywKICAic291cmNlcyI6IFsidml0ZS5jb25maWcuanMiXSwKICAic291cmNlc0NvbnRlbnQiOiBbImNvbnN0IF9fdml0ZV9pbmplY3RlZF9vcmlnaW5hbF9kaXJuYW1lID0gXCJFOlxcXFxwcm9qZWN0XFxcXGludGVydmlldy1ndWlkZVxcXFxpbnRlcnZpZXctZ3VpZGVcXFxcZnJvbnRlbmRcIjtjb25zdCBfX3ZpdGVfaW5qZWN0ZWRfb3JpZ2luYWxfZmlsZW5hbWUgPSBcIkU6XFxcXHByb2plY3RcXFxcaW50ZXJ2aWV3LWd1aWRlXFxcXGludGVydmlldy1ndWlkZVxcXFxmcm9udGVuZFxcXFx2aXRlLmNvbmZpZy5qc1wiO2NvbnN0IF9fdml0ZV9pbmplY3RlZF9vcmlnaW5hbF9pbXBvcnRfbWV0YV91cmwgPSBcImZpbGU6Ly8vRTovcHJvamVjdC9pbnRlcnZpZXctZ3VpZGUvaW50ZXJ2aWV3LWd1aWRlL2Zyb250ZW5kL3ZpdGUuY29uZmlnLmpzXCI7aW1wb3J0IHsgZGVmaW5lQ29uZmlnLCBsb2FkRW52IH0gZnJvbSAndml0ZSc7XG5pbXBvcnQgcmVhY3QgZnJvbSAnQHZpdGVqcy9wbHVnaW4tcmVhY3QnO1xuaW1wb3J0IHdhc20gZnJvbSAndml0ZS1wbHVnaW4td2FzbSc7XG5pbXBvcnQgdG9wTGV2ZWxBd2FpdCBmcm9tICd2aXRlLXBsdWdpbi10b3AtbGV2ZWwtYXdhaXQnO1xuLy8gaHR0cHM6Ly92aXRlanMuZGV2L2NvbmZpZy9cbmV4cG9ydCBkZWZhdWx0IGRlZmluZUNvbmZpZyhmdW5jdGlvbiAoX2EpIHtcbiAgICB2YXIgbW9kZSA9IF9hLm1vZGU7XG4gICAgdmFyIGVudiA9IGxvYWRFbnYobW9kZSwgcHJvY2Vzcy5jd2QoKSwgJycpO1xuICAgIHZhciBhcGlQcm94eVRhcmdldCA9IGVudi5WSVRFX0FQSV9QUk9YWV9UQVJHRVQgfHwgJ2h0dHA6Ly9sb2NhbGhvc3Q6ODA4MCc7XG4gICAgcmV0dXJuIHtcbiAgICAgICAgcGx1Z2luczogW1xuICAgICAgICAgICAgd2FzbSgpLFxuICAgICAgICAgICAgdG9wTGV2ZWxBd2FpdCgpLFxuICAgICAgICAgICAgcmVhY3QoKSxcbiAgICAgICAgXSxcbiAgICAgICAgYnVpbGQ6IHtcbiAgICAgICAgICAgIHJvbGx1cE9wdGlvbnM6IHtcbiAgICAgICAgICAgICAgICBvdXRwdXQ6IHtcbiAgICAgICAgICAgICAgICAgICAgbWFudWFsQ2h1bmtzOiB7XG4gICAgICAgICAgICAgICAgICAgICAgICAncmVhY3QtdmVuZG9yJzogWydyZWFjdCcsICdyZWFjdC1kb20nLCAncmVhY3Qtcm91dGVyLWRvbSddLFxuICAgICAgICAgICAgICAgICAgICAgICAgJ3VpLXZlbmRvcic6IFsnZnJhbWVyLW1vdGlvbicsICdsdWNpZGUtcmVhY3QnXSxcbiAgICAgICAgICAgICAgICAgICAgICAgIC8vIFx1OUFEOFx1NEVBRVx1NTY2OFx1NURGMlx1NjUzOVx1NEUzQSBwcmlzbS1saWdodCBcdTYzMDlcdTk3MDBcdTUyQTBcdThGN0RcdUZGMENcdTcyRUNcdTdBQ0JcdTYyMTBcdTU3NTdcdTRFRTVcdTRGQkZcdTZENEZcdTg5QzhcdTU2NjhcdTYzMDlcdTk3MDBcdTdGMTNcdTVCNThcbiAgICAgICAgICAgICAgICAgICAgICAgICdzeW50YXgtaGlnaGxpZ2h0ZXInOiBbJ3JlYWN0LXN5bnRheC1oaWdobGlnaHRlciddLFxuICAgICAgICAgICAgICAgICAgICAgICAgLy8gXHU1NkZFXHU4ODY4XHU0RTBFIE1hcmtkb3duIFx1NkUzMlx1NjdEM1x1NEY1M1x1NzlFRlx1OEY4M1x1NTkyN1x1RkYwQ1x1NTM1NVx1NzJFQ1x1NjIxMFx1NTc1N1x1OTA3Rlx1NTE0RFx1OTg3NVx1OTc2MiBjaHVuayBcdThEODVcdThGQzcgNTAwIGtCXG4gICAgICAgICAgICAgICAgICAgICAgICAnY2hhcnRzLXZlbmRvcic6IFsncmVjaGFydHMnXSxcbiAgICAgICAgICAgICAgICAgICAgICAgICdtYXJrZG93bi12ZW5kb3InOiBbJ3JlYWN0LW1hcmtkb3duJywgJ3JlbWFyay1nZm0nXSxcbiAgICAgICAgICAgICAgICAgICAgfSxcbiAgICAgICAgICAgICAgICB9LFxuICAgICAgICAgICAgfSxcbiAgICAgICAgfSxcbiAgICAgICAgc2VydmVyOiB7XG4gICAgICAgICAgICBob3N0OiAnMC4wLjAuMCcsXG4gICAgICAgICAgICBwb3J0OiA1MTczLFxuICAgICAgICAgICAgcHJveHk6IHtcbiAgICAgICAgICAgICAgICAnL2FwaSc6IHtcbiAgICAgICAgICAgICAgICAgICAgdGFyZ2V0OiBhcGlQcm94eVRhcmdldCxcbiAgICAgICAgICAgICAgICAgICAgY2hhbmdlT3JpZ2luOiB0cnVlLFxuICAgICAgICAgICAgICAgIH0sXG4gICAgICAgICAgICAgICAgJy93cyc6IHtcbiAgICAgICAgICAgICAgICAgICAgdGFyZ2V0OiBhcGlQcm94eVRhcmdldCxcbiAgICAgICAgICAgICAgICAgICAgY2hhbmdlT3JpZ2luOiB0cnVlLFxuICAgICAgICAgICAgICAgICAgICB3czogdHJ1ZSxcbiAgICAgICAgICAgICAgICB9LFxuICAgICAgICAgICAgfSxcbiAgICAgICAgfSxcbiAgICB9O1xufSk7XG4iXSwKICAibWFwcGluZ3MiOiAiO0FBQXFWLFNBQVMsY0FBYyxlQUFlO0FBQzNYLE9BQU8sV0FBVztBQUNsQixPQUFPLFVBQVU7QUFDakIsT0FBTyxtQkFBbUI7QUFFMUIsSUFBTyxzQkFBUSxhQUFhLFNBQVUsSUFBSTtBQUN0QyxNQUFJLE9BQU8sR0FBRztBQUNkLE1BQUksTUFBTSxRQUFRLE1BQU0sUUFBUSxJQUFJLEdBQUcsRUFBRTtBQUN6QyxNQUFJLGlCQUFpQixJQUFJLHlCQUF5QjtBQUNsRCxTQUFPO0FBQUEsSUFDSCxTQUFTO0FBQUEsTUFDTCxLQUFLO0FBQUEsTUFDTCxjQUFjO0FBQUEsTUFDZCxNQUFNO0FBQUEsSUFDVjtBQUFBLElBQ0EsT0FBTztBQUFBLE1BQ0gsZUFBZTtBQUFBLFFBQ1gsUUFBUTtBQUFBLFVBQ0osY0FBYztBQUFBLFlBQ1YsZ0JBQWdCLENBQUMsU0FBUyxhQUFhLGtCQUFrQjtBQUFBLFlBQ3pELGFBQWEsQ0FBQyxpQkFBaUIsY0FBYztBQUFBO0FBQUEsWUFFN0Msc0JBQXNCLENBQUMsMEJBQTBCO0FBQUE7QUFBQSxZQUVqRCxpQkFBaUIsQ0FBQyxVQUFVO0FBQUEsWUFDNUIsbUJBQW1CLENBQUMsa0JBQWtCLFlBQVk7QUFBQSxVQUN0RDtBQUFBLFFBQ0o7QUFBQSxNQUNKO0FBQUEsSUFDSjtBQUFBLElBQ0EsUUFBUTtBQUFBLE1BQ0osTUFBTTtBQUFBLE1BQ04sTUFBTTtBQUFBLE1BQ04sT0FBTztBQUFBLFFBQ0gsUUFBUTtBQUFBLFVBQ0osUUFBUTtBQUFBLFVBQ1IsY0FBYztBQUFBLFFBQ2xCO0FBQUEsUUFDQSxPQUFPO0FBQUEsVUFDSCxRQUFRO0FBQUEsVUFDUixjQUFjO0FBQUEsVUFDZCxJQUFJO0FBQUEsUUFDUjtBQUFBLE1BQ0o7QUFBQSxJQUNKO0FBQUEsRUFDSjtBQUNKLENBQUM7IiwKICAibmFtZXMiOiBbXQp9Cg==
