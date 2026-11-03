import { defineConfig } from "vitest/config";
import react from "@vitejs/plugin-react";

// https://vite.dev/config/
export default defineConfig({
  plugins: [react()],
  server: {
    host: "127.0.0.1",
    proxy: {
      "/api/copilot": "http://127.0.0.1:8081",
      "/core-api": {
        target: "http://127.0.0.1:8080",
        rewrite: (path) => path.replace(/^\/core-api/, ""),
      },
    },
  },
  test: {
    environment: "jsdom",
    setupFiles: "./src/test/setup.ts",
  },
});
