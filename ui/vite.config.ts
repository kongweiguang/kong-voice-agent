import path from "node:path";
import tailwindcss from "@tailwindcss/vite";
import react from "@vitejs/plugin-react";
import { defineConfig } from "vite";

/** Vite 构建配置，固定 React、Tailwind v4 与本地开发代理。 */
export default defineConfig({
  plugins: [react(), tailwindcss()],
  resolve: {
    alias: {
      "@": path.resolve(__dirname, "./src"),
    },
  },
  server: {
    port: 5173,
    proxy: {
      "/api": {
        target: "http://localhost:9877",
        changeOrigin: true,
      },
      "/ws": {
        target: "ws://localhost:9877",
        ws: true,
        changeOrigin: true,
      },
    },
  },
});
