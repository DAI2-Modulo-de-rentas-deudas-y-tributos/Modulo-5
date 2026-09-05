# Auditoría definitiva frontend/backend M5

Esta matriz describe el estado implementado de `feature/fullstack-real-integration`. En modo `VITE_USE_MOCKS=false`, las operaciones de negocio atraviesan `apiClient`, el controlador y servicio Spring correspondiente y PostgreSQL; no existe fallback a `mockDb` ante errores HTTP.

Convenciones: `UI/API` indica que el caso está conectado desde la pantalla y además cubierto por pruebas de contrato/servicio; `SQL/TC` indica persistencia comprobada por consultas SQL y Testcontainers PostgreSQL 17. Los bloqueos externos se documentan aparte y no se simulan como capacidades productivas.

| # | Feature | Front | Back | Postgres | E2E | Status |
|---:|---|---|---|---|---|---|
| 1 | Configuración de tributos | UI/API | catálogo y configuración versionada | tax_concept, tax_configuration | UI→API→SQL | PASS |
| 2 | Proponer versión | UI/API | create configuration | tax_configuration | UI→API→SQL | PASS |
| 3 | Submit | UI/API | submit workflow | tax_configuration, audit_entry | UI→API→SQL | PASS |
| 4 | Aprobar/rechazar configuración | UI/API | approve/reject | tax_configuration, audit_entry | UI→API→SQL | PASS |
| 5 | Liquidación individual | UI/API | liquidation service | liquidation, debt | UI→API→SQL | PASS |
| 6 | Liquidación masiva | UI/API | run preview/execute | liquidation_run, liquidation, debt | API/TC→SQL | PASS |
| 7 | Descuentos | UI/API | adjustment workflow | debt_adjustment, debt | API/TC→SQL | PASS |
| 8 | Exenciones | UI/API | exemption workflow | exemption_request, exemption | UI→API→SQL | PASS |
| 9 | Emisión de boletas | UI/API | billing service | bill, bill_debt | UI→API→SQL | PASS |
| 10 | Deuda consolidada | UI/API | taxpayer summary/debts | debt | UI→API→SQL | PASS |
| 11 | Pagos presenciales | UI/API | payment service | payment, payment_allocation | UI→API→SQL | PASS |
| 12 | Pagos electrónicos simulados | UI/API | payment/reconciliation | payment, electronic_reconciliation_* | API/TC→SQL | PASS |
| 13 | Pagos parciales | UI/API | payment allocation | payment, payment_allocation, debt | API/TC→SQL | PASS |
| 14 | Pago sin imputar | UI/API | payment service | payment | API/TC→SQL | PASS |
| 15 | Imputación posterior | UI/API | allocation service | payment_allocation, debt | API/TC→SQL | PASS |
| 16 | Sobrepago | UI/API | payment service | payment, credit_balance | API/TC→SQL | PASS |
| 17 | Generación saldo a favor | UI/API | credit service | credit_balance | API/TC→SQL | PASS |
| 18 | Aplicación saldo a favor | UI/API | credit application | credit_balance_application, debt | API/TC→SQL | PASS |
| 19 | Solicitud de reversión | UI/API | reversal workflow | payment_reversal_request | API/TC→SQL | PASS |
| 20 | Aprobación de reversión | UI/API | reversal approval | payment_reversal_request, audit_entry | API/TC→SQL | PASS |
| 21 | Ejecución de reversión | UI/API | transactional reversal | payment, payment_allocation, debt | API/TC→SQL | PASS |
| 22 | Comprobante | UI/API | receipt response | payment | UI/API→SQL | PASS |
| 23 | Simulación de plan | UI/API | plan simulation | consultas debt/configuration | UI/API→SQL | PASS |
| 24 | Solicitud de plan | UI/API | plan request | payment_plan_request | UI→API→SQL | PASS |
| 25 | Otorgamiento de plan | UI/API | grant plan | payment_plan, installment | API/TC→SQL | PASS |
| 26 | Pago de cuota | UI/API | installment allocation | installment, payment_allocation, debt | API/TC→SQL | PASS |
| 27 | Interés de financiación | UI/API | plan calculation/allocation | installment, payment_allocation | API/TC→SQL | PASS |
| 28 | Caducidad/incumplimiento | UI/API | due processing/expiration | installment, plan_expiration_request | API/TC→SQL | PASS |
| 29 | Refinanciación | UI/API | refinancing workflow | refinancing_request, payment_plan | API/TC→SQL | PASS |
| 30 | Solicitud de exención | UI/API | exemption request | exemption_request | UI→API→SQL | PASS |
| 31 | Aprobar/rechazar exención | UI/API | exemption resolution | exemption_request, exemption, audit_entry | UI/API→SQL | PASS |
| 32 | Indicadores | UI/API | agregaciones SQL | debt, payment, liquidation | API/TC→SQL | PASS |
| 33 | Auditoría | UI/API | audit service | audit_entry | API/TC→SQL | PASS |
| 34 | Preview recargos/intereses | UI/API | late charge preview | reglas y deuda, sin escritura | UI→API→SQL | PASS |
| 35 | Procesamiento de vencimientos | UI/API | idempotent due processor | due_date_processing, ajustes, cuotas | UI→API→SQL | PASS |
| 36 | Conciliación electrónica | UI/API | import/match/resolve | electronic_reconciliation_* | UI→API→SQL | PASS |

## Autenticación y autorización

- En modo API, el login usa `POST /api/v1/dev-auth/login`; `db.USERS` queda limitado a `VITE_USE_MOCKS=true`.
- Los usuarios demo se persisten con BCrypt y roles exactos `RENTAS`, `SUPERVISOR`, `CASHIER`, `AUDITOR` y `TAXPAYER`.
- Los endpoints demo sólo existen con `rentas.security.dev-mode=true`, cuyo default es `false`.
- La identidad que alimenta `X-Dev-*` proviene de la respuesta autenticada del backend. Un contribuyente no puede consultar recursos de otro (`403 FORBIDDEN_OWNERSHIP`).

## Contratos externos preservados

- M4: `BLOCKED_M4_TAXPAYER_RESOLUTION`; no se crean efectos económicos hasta recibir `establishmentId → taxpayer`.
- M7 inbound: contrato, DNI/CUIT, importe, idempotencia, rollback y concurrencia validados.
- Core/JWT productivo: `EXTERNAL_BLOCKED`.
- Broker y outbound M4/M7/M8: `EXTERNAL_BLOCKED`; no se inventaron contratos.
- CORS de producción: `EXTERNAL_BLOCKED` hasta conocer el dominio definitivo; desarrollo local usa el proxy Vite.

## Evidencia principal

- Se creó `integration.taxpayer` en el backend, se verificó su fila/hash en PostgreSQL y se inició sesión desde el frontend real.
- Configuración, liquidación, boleta, pago, plan y exención fueron enviados desde formularios reales y comprobados por HTTP y SQL.
- El backend se reinició sin borrar el volumen y los datos continuaron disponibles por API y portal.
- Recargos, vencimientos y conciliación se ejecutaron desde UI real y quedaron persistidos.
- OpenAPI expone 132 paths y 147 operaciones, sin endpoints públicos `/api/v1/events*`.
