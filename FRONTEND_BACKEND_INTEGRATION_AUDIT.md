# Auditoría frontend/backend M5

Fuente contractual: `ApiController`, `ApiDtos` y OpenAPI de `develop` en `ea0a7d78a261a1b7efdef801283de88b92630a9e`. Auditoría realizada antes de adaptar las ramas reales.

Convenciones: `Page→VM[]` significa que `pageItems` devuelve un array para la UI y conserva `number`, `size`, `totalElements`, `totalPages` y `last` en `array.page`. Las pantallas legacy sin paginador solicitan `size=100`, nunca listas infinitas. `DTO→VM` significa conversión explícita en `apiAdapters.js`.

## Servicios funcionales

| Front service | Método y path frontend | Método y path backend | Request front → backend | Response esperada ← backend | Paginación / enum / auth | Estado inicial |
|---|---|---|---|---|---|---|
| auth.login/logout | POST `/auth/*` | No existe | sesión demo local; no se inventa JWT | `{token,user}` local | Core pendiente | AUTH_BLOCKED |
| taxpayer.search | GET `/taxpayers?query&type` | GET `/taxpayers?q&type` | renombra filtro | Taxpayer VM ← TaxpayerResponse | Page→VM[]; identidad dev | REQUEST_MISMATCH |
| taxpayer.getById | GET `/taxpayers/{id}` | igual | igual | `displayName→name`, DNI/CUIT | ownership | RESPONSE_MISMATCH |
| taxConfig.list | GET `/tax-config` | GET `/tax-concepts` + GET `/tax-configurations` | compone por taxConceptId | concepto + versiones | 2×Page→VM[]; TASA↔FEE | PATH_MISMATCH |
| taxConfig.detail | GET `/tax-config/{code}` | concepts + configurations | code→id y composición | historial VM | size 100 | PATH_MISMATCH |
| taxConfig.proposeVersion | POST `/{code}/versions` | POST `/tax-configurations` | code→id; DTO real | TaxConfigurationResponse→VM | cálculo ES↔EN | REQUEST_MISMATCH |
| taxConfig.submitForApproval | POST `.../{version}/submit` | POST `/tax-configurations/{id}/submit` | code+version→id | configuración VM | RENTAS | PATH_MISMATCH |
| taxConfig.pendingApprovals | GET `/tax-config/pending` | GET configurations pending + concepts | composición | bandeja VM | Page→VM[]; SUPERVISOR | PATH_MISMATCH |
| taxConfig.resolveVersion | PUT `.../{version}/status` | POST `/{id}/approve` o `/reject` | status→acción | configuración VM | SUPERVISOR | PATH_MISMATCH |
| settlement.list | GET `/settlements` | GET `/liquidations` | filtros permitidos | LiquidationResponse→VM | Page→VM[]; LIQUIDATION↔SETTLEMENT | PATH_MISMATCH |
| settlement.generate | POST `/settlements` | POST `/liquidations` | code→taxConceptId; base→taxableBase | LiquidationResponse→VM | RENTAS | REQUEST_MISMATCH |
| settlement.previewBatch | POST `/settlements/batch/preview` | POST `/liquidation-runs`, luego `/{id}/preview` | taxpayers→items | RunDetail→preview VM | size 100 | PATH_MISMATCH |
| settlement.generateBatch | POST `/settlements/batch` | POST `/liquidation-runs`, luego preview | DTO real | run/items VM | workflow DRAFT real | PATH_MISMATCH |
| settlement.issue | POST `/{id}/issue` | GET `/liquidations/{id}` | create ya emite y genera deuda | estado real | no segundo issue | MISSING_BACKEND |
| debt.list | GET `/debts` | igual | filtros permitidos | DebtResponse→VM | Page→VM[]; PAID→SETTLED; overdue derivado | PAGINATION_MISMATCH |
| debt.accountStatement | GET `/taxpayers/{id}/account-statement` | GET `/{id}/summary` | igual | Summary→VM parcial | ownership | PATH_MISMATCH |
| debt.reportOverdue | POST `/{id}/report-overdue` | No existe | no se emula | error M8_OUTBOUND_PENDING | M8 outbound | MISSING_BACKEND |
| adjustment.list | GET `/debt-adjustments` | GET `/adjustments` | filtros | AdjustmentResponse→VM | Page→VM[] | PATH_MISMATCH |
| adjustment.request | POST `/debt-adjustments` | GET debt + POST `/adjustments` | importe nuevo→delta DISCOUNT/SURCHARGE | AdjustmentResponse→VM | vencimiento no soportado | REQUEST_MISMATCH |
| adjustment.resolve | PUT `/{id}/status` | POST `/{id}/approve|reject` | status→acción | AdjustmentResponse→VM | SUPERVISOR | PATH_MISMATCH |
| adjustment.execute | POST `/{id}/execute` | GET `/adjustments/{id}` | approve ya aplica | estado actual | — | MISSING_BACKEND |
| bill.list | GET `/bills` | igual | filtros | BillResponse→VM | Page→VM[] | PAGINATION_MISMATCH |
| bill.search | GET `/bills/search?query` | GET `/bills?q` | query→q | Bill VM | Page→VM[] | PATH_MISMATCH |
| bill.issue | POST `/bills` | igual | debtId→debtIds + taxpayerId/dueDate | Bill VM | RENTAS | REQUEST_MISMATCH |
| payment.list | GET `/payments` | igual | filtros | PaymentResponse→VM | Page→VM[]; CONFIRMED→REGISTERED | PAGINATION_MISMATCH |
| payment.register | POST `/payments` | igual | amountPaid/method/debtId→DTO + allocations | Payment VM | métodos ES→CASH/CARD/TRANSFER/WALLET | REQUEST_MISMATCH |
| payment.allocate | POST `/{id}/allocate` | POST `/{id}/allocations` | target + amount DTO | allocation DTO | XOR target | PATH_MISMATCH |
| payment.reverse | POST `/{id}/reverse` | POST `/{id}/reversal-requests` | reason DTO | reversal DTO | CASHIER | PATH_MISMATCH |
| credit.list | GET `/credit-balances` | igual | filtros | availableAmount→amount | Page→VM[] | RESPONSE_MISMATCH |
| credit.applicableDebts | GET `/{id}/applicable-debts` | GET taxpayer debts | taxpayerId requerido si disponible | Debt VM | Page→VM[] | MISSING_BACKEND |
| credit.apply | POST `/{id}/applications` | POST `/{id}/apply` | amount DTO | application DTO | RENTAS | PATH_MISMATCH |
| paymentPlan.list | GET `/payment-plans` | GET `/payment-plan-requests` | filtros | request VM | Page→VM[] | PATH_MISMATCH |
| paymentPlan.request | POST `/payment-plans` | POST `/payment-plan-requests` | DTO real; downPayment no es parte del alta | request VM | TAXPAYER | PATH_MISMATCH |
| paymentPlan.simulate | cálculo local legacy | POST `/payment-plans/simulations` | UI no aporta taxpayerId/debtIds | cálculo conservado | — | MISSING_FRONTEND |
| paymentPlan.escalate | POST `/{id}/escalate` | POST `/payment-plan-requests/{id}/submit-exception` | note→reason | request VM | RENTAS | PATH_MISMATCH |
| paymentPlan.resolve | PUT `/{id}/status` | POST `/{id}/grant|reject` | status→acción | request VM | RENTAS | PATH_MISMATCH |
| refinancing.eligiblePlans | GET `/refinancing/eligible` | GET `/payment-plans` | backend valida al solicitar | Plan VM + elegibilidad visible | Page→VM[] | MISSING_BACKEND |
| refinancing.simulate | cálculo local legacy | POST `/{planId}/refinancing/simulations` | UI no aporta planId | cálculo conservado | — | MISSING_FRONTEND |
| refinancing.request | POST `/payment-plans/refinancing` | POST `/payment-plans/{id}/refinancing-requests` | id en path; installments | response→VM | — | PATH_MISMATCH |
| refinancing.escalate | POST `.../{id}/escalate` | POST `/refinancing-requests/{id}/submit-exception` | note→reason | response→VM | RENTAS | PATH_MISMATCH |
| refinancing.resolve | PUT `.../{id}/status` | POST grant/reject o exception actions | status/rol→acción | response→VM | SUPERVISOR | PATH_MISMATCH |
| refinancing.list | GET `/payment-plans/refinancing` | GET `/refinancing-requests` | status | response→VM | Page→VM[] | PATH_MISMATCH |
| exemption.list | GET `/exemptions` | GET `/exemption-requests` | status | request VM | Page→VM[] | PATH_MISMATCH |
| exemption.advanceWorkflow | PUT `/{id}/workflow` | POST start-review/request-documentation/submit-resolution | estado→acción | request VM | RENTAS | PATH_MISMATCH |
| exemption.attachDocumentation | POST `/{id}/attachments` | POST `/{id}/documentation` por documento | referencia externa, no binario | request VM | TAXPAYER | REQUEST_MISMATCH |
| exemption.request | POST `/exemptions` | POST `/exemption-requests` | citizen/code→taxpayerId/taxConceptId | request VM | TAXPAYER ownership | REQUEST_MISMATCH |
| exemption.resolve | PUT `/{id}/status` | POST approve/reject | status→acción | exemption/request VM | SUPERVISOR | PATH_MISMATCH |
| ticket.list | GET `/tickets` | igual | filtros | TicketResponse→VM | Page→VM[] | PAGINATION_MISMATCH |
| ticket.updateStatus | PUT `/{id}/status` | POST assign/updates/request-information/complete/reject | status→caso de uso | Ticket VM | RENTAS | PATH_MISMATCH |
| event.list | GET `/events` | GET `/integrations/events` | filtros | IntegrationEvent VM | Page→VM[] | PATH_MISMATCH |
| event.retry | POST `/events/{id}/retry` | POST `/integrations/events/{eventId}/reprocess` | reason opcional | IntegrationEvent VM | no `/api/v1/events*` público | PATH_MISMATCH |

## Fachadas sin controlador agregado

| Operación frontend | Backend real usado | Request/response/página | Auth | Estado inicial |
|---|---|---|---|---|
| cashier.search | `/taxpayers?q` | Taxpayer VM, size 100 | CASHIER | PATH_MISMATCH |
| cashier.chargeContext | `/debts/{id}`, `/bills/{id}` o `/taxpayers/{id}` | DTO→VM | CASHIER | PATH_MISMATCH |
| cashier.registerCounterPayment | POST `/payments` | RegisterPaymentRequest→Payment VM | CASHIER | PATH_MISMATCH |
| cashier.receipt | `/payments/{id}/receipt` | ReceiptResponse | CASHIER | PATH_MISMATCH |
| cashier.taxpayerFile | `/taxpayers/{id}/summary` | resumen parcial | CASHIER | MISSING_BACKEND |
| cashier.agents | ninguno; usuarios son de Core | `[]`, no mockDb | CASHIER | MISSING_BACKEND |
| cashier.dailySummary | `/payments?from` | Page→VM[]; agregado completo ausente | CASHIER | MISSING_BACKEND |
| audit.dashboard | `/indicators/summary` | summary→dashboard | AUDITOR | PATH_MISMATCH |
| audit.taxpayers/detail | `/taxpayers*` | Page/DTO→VM | AUDITOR | PATH_MISMATCH |
| audit.concepts/detail | `/tax-concepts*` | Page/DTO→VM | AUDITOR | PATH_MISMATCH |
| audit.settlements/detail | `/liquidations*` | Page/DTO→VM | AUDITOR | PATH_MISMATCH |
| audit.debts/detail | `/debts*` | Page/DTO→VM | AUDITOR | PATH_MISMATCH |
| audit.payments/detail | `/payments*` | Page/DTO→VM | AUDITOR | PATH_MISMATCH |
| audit.reversals/detail | `/payment-reversals*` | Page/DTO→VM | AUDITOR | PATH_MISMATCH |
| audit.plans/detail | `/payment-plan-requests*` | Page/DTO→VM | AUDITOR | PATH_MISMATCH |
| audit.exemptions/detail | `/exemption-requests*` | Page/DTO→VM | AUDITOR | PATH_MISMATCH |
| audit.tickets/detail | `/tickets*` | Page/DTO→VM | backend sólo RENTAS | AUTH_BLOCKED |
| audit.integrations/detail | `/integrations/events*` | Page/DTO→VM | AUDITOR | PATH_MISMATCH |
| audit.auditTrail/detail | `/audit*` | AuditEntry→VM | AUDITOR | PATH_MISMATCH |
| audit.indicators/breakdown | `/indicators/summary` y específicos | summary real; breakdown genérico ausente | AUDITOR | MISSING_BACKEND |
| portal.accountSummary | `/taxpayers/{id}/summary` | Summary→VM parcial | TAXPAYER ownership | PATH_MISMATCH |
| portal.notices | `/taxpayers/{id}/debts` | avisos derivados | TAXPAYER ownership | MISSING_BACKEND |
| portal.debts/bills/payments | `/taxpayers/{id}/{resource}` | Page→VM[] | TAXPAYER ownership | PATH_MISMATCH |
| portal.paymentPlans | `/taxpayers/{id}/payment-plan-requests` | Page→VM[] | TAXPAYER ownership | PATH_MISMATCH |
| portal.exemptions | `/taxpayers/{id}/exemption-requests` | Page→VM[] | TAXPAYER ownership | PATH_MISMATCH |
| portal requests | delegan a services reales | DTO real | TAXPAYER ownership | PATH_MISMATCH |
| dashboard.metrics | `/indicators/summary` | counts opcionales sin fuente quedan en cero; errores HTTP no se ocultan | RENTAS | PATH_MISMATCH |

## Conclusiones

- Ramas reales auditadas: 88, excluyendo el helper de fecha que también consulta `USE_MOCKS`.
- Todas pasan por la capa anticorrupción antes de `fetch`; los nombres legacy pueden permanecer dentro del servicio, pero no se crean aliases backend.
- Se preserva metadata de página y se limita compatibilidad a 100 filas.
- El dashboard convierte a cero únicamente métricas/counts opcionales ausentes del summary; no transforma respuestas arbitrarias ni oculta errores HTTP.
- No se falsifican capacidades: agentes Core, reporte outbound M8, ajuste de vencimiento, breakdown genérico y agregados completos de caja quedan pendientes.
- `CORS_DEPLOYMENT_PENDING`: local usa proxy Vite; falta la URL final de Amplify para una lista explícita de origins.
