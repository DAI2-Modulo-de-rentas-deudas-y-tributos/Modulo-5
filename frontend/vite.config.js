import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: { port: 5173 },
  test: {
    // jsdom sólo para las pruebas de UI; las de dominio no necesitan DOM.
    environment: "jsdom",
    globals: false,
    restoreMocks: true,
  },
});
