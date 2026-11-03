# Pruebas de punta a punta (Playwright)

Corren contra el sistema completo levantado con `docker compose up -d --build` y usan los datos
que carga `demo-setup` (usuarios DEMO, contribuyentes `QA Ciudadano A/B`, deudas y una boleta).
El CI las ejecuta en el job `compose` después del smoke test; el reporte queda como artefacto
`playwright-report`.

| Archivo | Escenario |
|---|---|
| `acceso.e2e.js` | Cada rol ingresa a su área y cierra sesión; contraseña incorrecta; BFLA y BOLA. |
| `portal.e2e.js` | El contribuyente consulta sus deudas y su boleta. |
| `cobro.e2e.js` | La caja registra un pago parcial y el contribuyente lo ve en "Mis pagos". |
| `plan.e2e.js` | El contribuyente pide un plan de pagos y Rentas lo recibe pendiente. |
| `vencimientos.e2e.js` | Rentas procesa los vencimientos y la deuda atrasada queda vencida. |
| `accesibilidad.e2e.js` | axe (WCAG 2.2 AA) sin violaciones críticas ni serias y sin scroll horizontal a 360, 768 y 1280 px. |

Para correrlas en local, desde `frontend/` y con el sistema levantado:

```shell
npm install
npx playwright install chromium
cd .. && sh scripts/compose/e2e-test.sh   # lee la contraseña DEMO del .env
```

`E2E_BASE_URL` cambia la URL del frontend (por defecto `http://localhost:5173`). Los escenarios
de cobro y de plan modifican datos; sobre una base recién creada pasan siempre.
