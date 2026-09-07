# Frontend

Aplicación web React del Módulo 5 — Rentas. Contiene las dos áreas de trabajo del
módulo, con login común y navegación separada por rol:

- **Personal de Rentas** (`/rentas`) — back-office: liquidaciones, deudas, boletas,
  pagos, planes, exenciones, tickets y, para el supervisor, la bitácora de eventos.
- **Caja** (`/caja`) — ventanilla del cajero: cobros, consulta del padrón, pagos del
  día y búsqueda de boletas. El cajero cobra e imprime comprobantes; no liquida, no
  resuelve planes ni exenciones y no reversa pagos.
- **Auditoría** (`/auditor`) — control transversal de sólo lectura: contribuyentes,
  conceptos, liquidaciones, deudas, pagos, planes, exenciones, tickets, integraciones,
  registro de auditoría e indicadores. El auditor observa todo el circuito y no
  modifica ninguna entidad: `auditService` no expone una sola operación de escritura.
- **Portal del contribuyente** (`/portal`) — la única área de cara al ciudadano: su
  resumen de cuenta con avisos, deudas, boletas, pagos, planes y exenciones. Consulta
  su propio legajo y puede iniciar dos trámites —pedir un plan de pago, que es la vía
  para conseguir más plazo, y pedir una exención—; no registra pagos ni resuelve nada.

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

- `VITE_API_BASE_URL` — vacía en desarrollo local para usar el proxy de Vite;
  en un build desplegado contiene el origen HTTPS del backend. Ninguna variable
  `VITE_*` lleva secretos.
- `VITE_AUTH_MODE` — `mock` usa la autenticación DEMO/DEV real del backend;
  `core` queda reservado hasta integrar el contrato JWT.
- `VITE_DEV_IDENTITY_HEADERS` — `true` sólo contra un backend con dev-mode habilitado.

El frontend no incluye usuarios ni datos de negocio alternativos. Los usuarios DEMO
se crean en PostgreSQL cuando el backend arranca con
`RENTAS_DEMO_BOOTSTRAP_PASSWORD`; la contraseña se define fuera del repositorio.

## Estructura

```text
src/
  components/ui/        Kit compartido del design system (no modificar; extender por props)
  components/layout/    Sidebar, Topbar, Footer y el shell de cada área de trabajo
  components/common/    Piezas del back-office: DataTable, Modal, FilterBar, Card…
  components/caja/      Comprobante de ventanilla y su reimpresión
  components/auditoria/ Fichas, historiales y gráficos del área de control
  config/modules.js     Módulos de Rentas y su visibilidad por rol
  config/cajaModules.js Módulos de la ventanilla de caja
  config/auditoriaModules.js  Módulos del área de auditoría
  config/portalModules.js     Módulos del portal del contribuyente
  config/workspaces.js  A qué panel entra cada rol
  context/              Sesión del agente municipal
  pages/                Login, y una página por módulo en pages/{rentas,caja,auditoria,portal}/
  services/             Cliente HTTP, adaptadores del contrato y servicios de negocio
```

Los gráficos de Indicadores son de una sola serie y usan `#2563A8` como relleno de
datos —un paso más claro del navy institucional, validado por contraste sobre blanco—
mientras que `#0F2C59` queda para texto, que es su rol en el design system. Toda
visualización ofrece además su vista de tabla.

El comprobante de caja se imprime desde el navegador y las boletas PDF se descargan
desde el endpoint protegido del backend.

## Design system

Tipografía Inter, navy `#0F2C59` y coral `#D63031` como acento, fondo `#FAFAFA`.
Los tokens viven en `src/index.css` (`@theme` de Tailwind v4). Los componentes de
`src/components/ui/` son la fuente canónica del UI kit: reutilizarlos siempre en vez
de escribir markup o colores nuevos.
