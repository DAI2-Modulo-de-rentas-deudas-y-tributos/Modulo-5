import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    // Proxy sólo de desarrollo: evita ampliar CORS mientras la URL de deployment no existe.
    proxy: { "/api": { target: "http://localhost:8080", changeOrigin: true } },
  },
  test: {
    // jsdom sólo para las pruebas de UI; las de dominio no necesitan DOM.
    environment: "jsdom",
    globals: false,
    restoreMocks: true,
    // Las pruebas E2E de UI escriben formularios completos con user-event. En hosts
    // Windows con carga de Docker pueden superar el default de 5 s sin estar colgadas.
    testTimeout: 15000,
  },
});
