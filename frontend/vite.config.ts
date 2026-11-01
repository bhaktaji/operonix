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

// The API defaults to port 8080. Set VITE_API_PROXY_TARGET when Spring Boot runs on another port
// (for example when 8080 is already taken on the developer's machine).
const API_TARGET = process.env.VITE_API_PROXY_TARGET ?? "http://localhost:8080";

export default defineConfig({
  plugins: [react()],
  resolve: {
    alias: { "@": path.resolve(__dirname, "./src") },
  },
  server: {
    port: 5173,
    proxy: {
      "/api": sameOriginProxy(API_TARGET),
      "/actuator": sameOriginProxy(API_TARGET),
    },
  },
});