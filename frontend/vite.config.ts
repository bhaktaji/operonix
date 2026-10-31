import { defineConfig } from "vite";
import type { ProxyOptions } from "vite";
import react from "@vitejs/plugin-react";
import path from "node:path";

/**
 * The browser talks to the dev server, which proxies to the API on the same origin. Forwarding the
 * browser's Origin header would make a same-origin call look like a cross-origin one to Spring's CORS
 * filter, so it is stripped here. Production serves the SPA and the API from one origin and needs no
 * proxy at all.
 */
function sameOriginProxy(target: string): ProxyOptions {
  return {
    target,
    changeOrigin: true,
    configure: (proxy) => {
      proxy.on("proxyReq", (proxyRequest) => {
        proxyRequest.removeHeader("origin");
      });
    },
  };
}

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { "@": path.resolve(__dirname, "./src") },
  },
  server: {
    port: 5173,
    proxy: {
      "/api": sameOriginProxy("http://localhost:8080"),
      "/actuator": sameOriginProxy("http://localhost:8080"),
    },
  },
});