# Frontend

Aplicación web React del Módulo 5 — Rentas. Contiene el **área de trabajo de
Personal de Rentas**: login y los módulos funcionales de la operación diaria.

## Comandos

```shell
npm install          # dependencias
npm run dev          # servidor de desarrollo en http://localhost:5173
npm test             # pruebas (vitest, sin modo interactivo)
npm run build        # build estático en dist/ — lo que publica Amplify
npm run preview      # sirve el build de producción
```

Para correr un archivo o un test puntual:

```shell
npm test -- src/services/rentasService.test.js
npm test -- -t "cancela la deuda cuando el pago cubre el saldo"
```

## Configuración

Copiar `.env.example` a `.env`:

- `VITE_API_BASE_URL` — URL pública del backend. Ninguna variable `VITE_*` lleva secretos.
- `VITE_USE_MOCKS` — `true` usa el dataset local de `src/services/mockDb.js`. Mientras el
  backend no exista, la app funciona completa contra esos datos. Con el backend arriba,
  poner `false`: las firmas de `src/services/rentasService.js` no cambian.

Usuarios del dataset de demostración: `mrivas` / `rentas123` (Personal de Rentas) y
`jlopez` / `rentas123` (Supervisor).

## Estructura

```text
src/
  components/ui/        Kit compartido del design system (no modificar; extender por props)
  components/layout/    Sidebar, Topbar, Footer y shell de los módulos
  components/common/    Piezas del back-office: DataTable, Modal, FilterBar, Card…
  config/modules.js     Módulos funcionales del área y su visibilidad por rol
  context/              Sesión del agente municipal
  pages/                Login, panel de inicio y una página por módulo en pages/rentas/
  services/             Cliente HTTP, dataset de demostración y servicios de negocio
```

## Design system

Tipografía Inter, navy `#0F2C59` y coral `#D63031` como acento, fondo `#FAFAFA`.
Los tokens viven en `src/index.css` (`@theme` de Tailwind v4). Los componentes de
`src/components/ui/` son la fuente canónica del UI kit: reutilizarlos siempre en vez
de escribir markup o colores nuevos.
