import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

// Mismas cabeceras que publica Amplify (infra/modules/hosting, SCRUM-798), para el stack local de
// compose.yaml. connect-src 'self' porque la API llega por el proxy; HSTS no aplica sobre http.
// Sólo en `vite preview`: el servidor de desarrollo inyecta scripts inline que la CSP bloquearía.
const securityHeaders = {
  "Content-Security-Policy": [
    "default-src 'self'",
    "connect-src 'self'",
    "style-src 'self' 'unsafe-inline' https://fonts.googleapis.com",
    "font-src 'self' https://fonts.gstatic.com",
    "img-src 'self' data:",
    "object-src 'none'",
    "base-uri 'self'",
    "frame-ancestors 'none'",
  ].join("; "),
  "X-Frame-Options": "DENY",
  "X-Content-Type-Options": "nosniff",
  "Referrer-Policy": "strict-origin-when-cross-origin",
};

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    // Proxy local (también lo usa `vite preview` en compose.yaml): evita ampliar CORS.
    proxy: { "/api": { target: process.env.API_PROXY_TARGET ?? "http://localhost:8080", changeOrigin: true } },
  },
  preview: { headers: securityHeaders },
  test: {
    // jsdom sólo para las pruebas de UI; las de dominio no necesitan DOM.
    environment: "jsdom",
    globals: false,
    restoreMocks: true,
    setupFiles: ["./src/test/setup.js"],
    // La suite de dominio/UI usa el dataset mock de forma explícita. En runtime
    // VITE_USE_MOCKS ausente o false habla con la API real.
    env: { VITE_USE_MOCKS: "true" },
    // Las pruebas E2E de UI escriben formularios completos con user-event. En hosts
    // Windows con carga de Docker pueden superar el default de 5 s sin estar colgadas.
    testTimeout: 15000,
    coverage: {
      provider: "v8",
      // lcov con rutas desde la raíz del repo (frontend/src/...), que es donde las busca SonarQube Cloud.
      reporter: ["text", "html", ["lcov", { projectRoot: ".." }]],
      include: ["src/**/*.{js,jsx}"],
      exclude: ["src/**/*.{test,spec}.{js,jsx}", "src/**/__tests__/**", "src/test/**"],
    },
  },
});
