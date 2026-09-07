# Auditoría contractual M7 y API REST de M5

Fecha de corte: 2026-08-31. Rama de trabajo inspeccionada: `feature/backend-rentas-hardening`. Este documento describe el estado local; no implica publicación, merge ni despliegue.

## 1. Alcance y método

Se contrastó el contrato documental `infractionConfirmed` campo por campo contra los DTOs, consumers, persistencia, idempotencia, reproceso y pruebas existentes. También se inspeccionaron todas las anotaciones HTTP de `ApiController`, sus DTOs, roles, servicios y respuestas; se comprobó el OpenAPI con MockMvc.

No se modificaron frontend, infraestructura, Core/JWT ni el adapter de broker. No se agregó un endpoint HTTP para ingerir eventos: el contrato es de mensajería y el único POST técnico relacionado es el reproceso autorizado de eventos ya registrados.

## 2. Comparación M7 campo por campo

| Campo contractual | Estado previo | Estado actual | Uso en M5 |
|---|---|---|---|
| `eventId: UUID` | MISMATCH: envelope genérico `String` | MATCH | idempotencia técnica en `ProcessedEvent` y trazabilidad |
| `eventType=infractionConfirmed` | MISMATCH: tipo genérico | MATCH, valor exacto | selección del consumer |
| `occurredAt: OffsetDateTime` | MATCH parcial | MATCH | fecha del evento |
| `sourceModule=transito` | MISMATCH: se esperaba `M7` | MATCH externo; normaliza a `M7` interno | routing controlado y auditoría canónica |
| `data.infractionId: UUID` | MISMATCH: `externalReferenceId` genérico | MATCH | clave de negocio de obligación externa |
| `data.debtorId: String` | MISMATCH: `taxpayerExternalId` | MATCH | resolución del contribuyente |
| `data.debtorIdType: DNI/CUIT` | MISMATCH: `TaxpayerType` interno | MATCH | DNI → ciudadano; CUIT → organización |
| `data.licensePlate` | MISSING | MATCH | validación y payload técnico; no se replica en dominio económico |
| `data.infractionType` | MISSING | MATCH | payload técnico; no se replica |
| `data.infractionDateTime` | MISSING | MATCH | traza y fecha interna de exigibilidad |
| `data.baseAmount` | MISSING | MATCH | validación/traza; no determina la deuda |
| `data.aggravatingFactors[]` | MISSING | MATCH; admite lista vacía | payload técnico |
| `data.finalAmount` | MISMATCH: se usaba `amount` genérico | MATCH | importe de `ExternalObligation` y `Debt` |
| `data.inspectorId` | MISSING | MATCH | payload técnico |
| `data.location.street` | MISSING | MATCH | payload técnico |
| `data.location.crossStreet` | MISSING | MATCH | payload técnico |
| `data.location.latitude` | MISSING | MATCH | payload técnico |
| `data.location.longitude` | MISSING | MATCH | payload técnico |
| `dueDate` local previo | EXTRA_LOCAL, no existe en M7 | NOT_REQUIRED_TO_PERSIST como dato externo | la fecha de infracción se proyecta explícitamente a la fecha exigible requerida por el esquema local |

No hay campos del contrato descartados sin traza: el envelope exacto se serializa en `IntegrationEventLog.payload`. M5 persiste como dominio únicamente contribuyente resuelto, referencia de infracción, importe final, obligación y deuda.

## 3. Flujo económico e idempotencia

1. `ConfirmedM7Consumer` valida Bean Validation, `eventType` exacto y `sourceModule` exacto.
2. `ExternalObligationService` normaliza `transito` a `M7` y busca al contribuyente por `(CITIZEN,DNI)` o `(ORGANIZATION,CUIT)`, con fallback a `externalId` sólo para referencias históricas.
3. En una transacción registra/procesa el evento y crea una `ExternalObligation` por `infractionId`.
4. Crea una única `Debt` vinculada a esa obligación usando exclusivamente `finalAmount`.
5. No crea pago, imputación ni boleta.

La protección usa dos niveles: `eventId` en `ProcessedEvent` y clave de negocio `(source_module, external_type, external_reference_id)` para `infractionId`. La migración V11 agrega además unicidad de `debt.external_obligation_id` y de DNI/CUIT por tipo de contribuyente. Un evento repetido y dos eventos distintos para la misma infracción no duplican efectos económicos; la carrera concurrente queda cerrada por constraints de PostgreSQL/H2 además del chequeo aplicativo.

Ante contribuyente ausente se registra estado `ERROR`; el reproceso técnico vuelve a consumir el payload M7 exacto cuando la referencia ya existe. Una validación inválida no crea obligación ni deuda. La caída/reintento no deja efectos parciales porque el alta económica es transaccional.

## 4. Contratos que permanecen pendientes

- M7 outbound: nombres, payloads y semántica de pago registrado, reversión y deuda saldada.
- M7 adicional: anulación de infracción, acarreo y estadía.
- M4: `TasaHabilitacionGenerada`, `MultaComercialGenerada`, `HabilitacionSuspendida` y payloads definitivos.
- Core/JWT: identidad y claims productivos.
- Broker: tecnología, topics/queues, versionado de schemas, ACK, retry y DLQ.

No se implementó ni simuló ninguno de esos contratos.

## 5. Resultado global de la API

| Métrica | Resultado |
|---|---:|
| Paths OpenAPI | 122 |
| Operaciones | 136 |
| GET | 74 |
| POST | 59 |
| PATCH | 3 |
| PUT | 0 |
| DELETE | 0 |
| Endpoints públicos `/api/v1/events*` | 0 |

### Resumen de clasificación

| Grupo | Total | OK | Corregidos en controllers | Pendientes/review | Problemas encontrados |
|---|---:|---:|---:|---:|---|
| GET | 74 | 57 | 0 | 17 | N+1, escaneos completos o colecciones hijas no paginadas; sin fuga de ownership demostrada |
| POST | 59 | 57 | 0 | 2 | idempotencia HTTP de pago a definir; eventual outbound M7 sin contrato |
| PATCH | 3 | 3 | 0 | 0 | ninguno |
| PUT | 0 | 0 | 0 | 0 | no existe |
| DELETE | 0 | 0 | 0 | 0 | no hay borrado físico público |

No se encontraron endpoints `BUG`, `DUPLICATE`, `UNUSED` o `SECURITY_RISK`. Los riesgos de performance quedan como `REVIEW`; no justificaban reescribir servicios en esta adaptación.

Todos los retornos de negocio son DTOs; el documento de boleta retorna `ResponseEntity<byte[]>`. No se detectaron entidades JPA expuestas directamente. Los listados principales usan `Page`; los listados hijos no paginados se señalan como `REVIEW` cuando pueden crecer. Las operaciones de contribuyente verifican ownership en controller o service. La prueba HTTP fue reforzada para comprobar ownership de resumen y de una boleta concreta.

Estados usados en el inventario:

- `OK`: contrato, DTO, rol, ownership y forma de respuesta coherentes con el caso de uso.
- `REVIEW-PERF`: correcto funcionalmente, pero con consulta N+1, escaneo total o colección potencialmente creciente.
- `PENDING_EXTERNAL_CONTRACT`: la operación local existe, pero una notificación externa asociada no puede declararse integrada.

Todos los paths siguientes llevan el prefijo `/api/v1` y pertenecen a `ApiController`. `R=RENTAS`, `S=SUPERVISOR`, `C=CASHIER`, `A=AUDITOR`, `T=TAXPAYER`, `Tech=TECHNICAL`.

## 6. Inventario completo — GET (74)

| Path | Handler / servicio | Respuesta / HTTP | Roles | Caso y estado |
|---|---|---|---|---|
| `/taxpayers` | `taxpayers` / filtered query | `Page<TaxpayerResponse>` / 200 | R,S,C,A | listar y filtrar; OK |
| `/taxpayers/{id}` | `taxpayer` / repository | `TaxpayerResponse` / 200 | R,S,C,A | detalle interno; OK |
| `/taxpayers/{id}/summary` | `taxpayerSummary` / query service | `TaxpayerSummaryResponse` / 200 | R,S,A,T | resumen propio; REVIEW-PERF (planes se recorren completos) |
| `/tax-concepts` | `concepts` / filtered query | `Page<TaxConceptResponse>` / 200 | R,S,A | catálogo; OK |
| `/tax-concepts/{id}` | `concept` / repository | `TaxConceptResponse` / 200 | R,S,A | detalle; OK |
| `/tax-configurations` | `configurations` / filtered query | `Page<TaxConfigurationResponse>` / 200 | R,S,A | configuraciones; OK |
| `/tax-configurations/{id}` | `configuration` / repository | `TaxConfigurationResponse` / 200 | R,S,A | detalle; OK |
| `/liquidations` | `liquidations` / query + liquidation service | `Page<LiquidationResponse>` / 200 | R,S,A | listado; REVIEW-PERF (componentes por fila) |
| `/liquidations/{id}` | `liquidation` / liquidation service | `LiquidationResponse` / 200 | R,S,A | detalle; OK |
| `/liquidation-runs` | `liquidationRuns` / filtered query | `Page<LiquidationRunResponse>` / 200 | R,S,A | corridas masivas; OK |
| `/liquidation-runs/{id}` | `liquidationRun` / run service | `LiquidationRunDetailResponse` / 200 | R,S,A | detalle; OK |
| `/adjustments` | `adjustments` / filtered query | `Page<AdjustmentResponse>` / 200 | R,S,A | ajustes; OK |
| `/adjustments/{id}` | `adjustment` / adjustment service | `AdjustmentResponse` / 200 | R,S,A | detalle; OK |
| `/debts` | `debts` / query + response service | `Page<DebtResponse>` / 200 | R,S,C,A | listado; REVIEW-PERF (plan activo por fila) |
| `/debts/{id}` | `debt` / response service | `DebtResponse` / 200 | R,S,C,A,T | detalle con ownership; OK |
| `/taxpayers/{id}/debts` | `taxpayerDebts` / filtered query | `Page<DebtResponse>` / 200 | R,S,C,A,T | deudas propias; REVIEW-PERF (plan activo por fila) |
| `/taxpayers/{id}/debts/summary` | `taxpayerDebtSummary` / query service | `DebtSummaryResponse` / 200 | R,S,A,T | resumen propio; OK |
| `/bills` | `bills` / query + response service | `Page<BillResponse>` / 200 | R,C,A | boletas; REVIEW-PERF (deudas por fila) |
| `/bills/{id}` | `bill` / billing service | `BillResponse` / 200 | R,C,A,T | detalle con ownership; OK |
| `/taxpayers/{id}/bills` | `taxpayerBills` / repository | `Page<BillResponse>` / 200 | R,C,A,T | boletas propias; REVIEW-PERF (deudas por fila) |
| `/bills/{id}/document` | `billDocument` / billing + PDF | PDF / 200 | R,C,T | documento con ownership; OK |
| `/payments` | `payments` / filtered query | `Page<PaymentResponse>` / 200 | R,S,C,A | pagos; OK |
| `/payments/{id}` | `payment` / repository | `PaymentResponse` / 200 | R,S,C,A,T | detalle con ownership; OK |
| `/taxpayers/{id}/payments` | `taxpayerPayments` / repository | `Page<PaymentResponse>` / 200 | R,A,T | pagos propios; OK |
| `/payments/{id}/receipt` | `receipt` / repository | `ReceiptResponse` / 200 | C,R,A,T | comprobante con ownership; OK |
| `/payments/{id}/allocations` | `paymentAllocations` / repository | `List<PaymentAllocationResponse>` / 200 | R,A | imputaciones de un pago; REVIEW-PERF (sin página) |
| `/payment-allocations` | `paymentAllocations` / filtered query | `Page<PaymentAllocationResponse>` / 200 | R,A | imputaciones; OK |
| `/payments/unallocated` | `unallocatedPayments` / repository | `Page<PaymentResponse>` / 200 | R | pagos no imputados; OK |
| `/electronic-payments/{paymentId}` | `electronic` / electronic service | `ElectronicPaymentResponse` / 200 | T,A | consulta propia; OK |
| `/credit-balances` | `creditBalances` / filtered query | `Page<CreditBalanceResponse>` / 200 | R,A | saldos a favor; OK |
| `/credit-balances/{id}` | `creditBalance` / credit service | `CreditBalanceResponse` / 200 | R,A,T | detalle con ownership; OK |
| `/taxpayers/{id}/credit-balances` | `taxpayerCredits` / repository | `List<CreditBalanceResponse>` / 200 | R,A,T | saldos propios; REVIEW-PERF (sin página) |
| `/payment-reversals` | `reversals` / filtered query | `Page<PaymentReversalResponse>` / 200 | R,S,A | reversiones; OK |
| `/payment-reversals/{id}` | `reversal` / repository | `PaymentReversalResponse` / 200 | R,S,A | detalle; OK |
| `/payment-plan-configurations` | `planConfigurations` / filtered query | `Page<PaymentPlanConfigurationResponse>` / 200 | R,S,A | configuración; OK |
| `/payment-plan-configurations/{id}` | `planConfiguration` / repository | `PaymentPlanConfigurationResponse` / 200 | R,S,A | detalle; OK |
| `/payment-plan-requests` | `planRequests` / filtered query | `Page<PaymentPlanRequestResponse>` / 200 | R,S,A | solicitudes; OK |
| `/payment-plan-requests/{id}` | `planRequest` / plan workflow | `PaymentPlanRequestResponse` / 200 | R,S,A,T | detalle con ownership; OK |
| `/taxpayers/{id}/payment-plan-requests` | `taxpayerPlanRequests` / repository | `Page<PaymentPlanRequestResponse>` / 200 | R,A,T | solicitudes propias; OK |
| `/payment-plans` | `plans` / filtered query | `Page<PaymentPlanResponse>` / 200 | R,S,A | planes; OK |
| `/payment-plans/{id}` | `plan` / plan workflow | `PaymentPlanResponse` / 200 | R,S,A,T | detalle con ownership; OK |
| `/taxpayers/{id}/payment-plans` | `taxpayerPlans` / repository | `Page<PaymentPlanResponse>` / 200 | R,S,A,T | planes propios; OK |
| `/payment-plans/{id}/installments` | `installments` / plan workflow | `List<InstallmentResponse>` / 200 | R,S,A,T | cuotas con ownership; REVIEW-PERF (sin página, acotado por plan) |
| `/payment-plans/{planId}/installments/{installmentId}` | `installment` / plan workflow | `InstallmentResponse` / 200 | R,S,A,T | cuota con ownership; OK |
| `/payment-plans/defaulted` | `defaultedPlans` / plan workflow | `Page<PaymentPlanResponse>` / 200 | R,S,A | planes caídos; REVIEW-PERF (escaneo previo completo) |
| `/payment-plan-expirations` | `planExpirations` / filtered query | `Page<PlanExpirationResponse>` / 200 | R,S,A | caducidades; OK |
| `/payment-plan-expirations/{id}` | `planExpiration` / plan workflow | `PlanExpirationResponse` / 200 | R,S,A | detalle; OK |
| `/refinancing-requests` | `refinancingRequests` / filtered query | `Page<RefinancingRequestResponse>` / 200 | R,S,A | refinanciaciones; OK |
| `/refinancing-requests/{id}` | `refinancingRequest` / plan workflow | `RefinancingRequestResponse` / 200 | R,S,A,T | detalle con ownership; OK |
| `/exemption-requests` | `exemptionRequests` / filtered query | `Page<ExemptionRequestResponse>` / 200 | R,S,A | solicitudes; OK |
| `/exemption-requests/{id}` | `exemptionRequest` / exemption service | `ExemptionRequestResponse` / 200 | R,S,A,T | detalle con ownership; OK |
| `/taxpayers/{id}/exemption-requests` | `taxpayerExemptionRequests` / repository | `Page<ExemptionRequestResponse>` / 200 | R,A,T | solicitudes propias; OK |
| `/exemptions` | `exemptions` / filtered query | `Page<ExemptionResponse>` / 200 | R,S,A | exenciones; OK |
| `/exemptions/{id}` | `exemption` / repository | `ExemptionResponse` / 200 | R,S,A,T | detalle con ownership; OK |
| `/taxpayers/{id}/exemptions` | `taxpayerExemptions` / repository | `List<ExemptionResponse>` / 200 | R,A,T | exenciones propias; REVIEW-PERF (sin página) |
| `/tickets` | `tickets` / filtered query | `Page<TicketResponse>` / 200 | R | tickets M2; OK |
| `/tickets/{id}` | `ticket` / ticket service | `TicketResponse` / 200 | R | detalle; OK |
| `/social-benefits` | `socialBenefits` / filtered query | `Page<SocialBenefitResponse>` / 200 | R,A | beneficios M8; OK |
| `/social-benefits/{id}` | `socialBenefit` / repository | `SocialBenefitResponse` / 200 | R,A,T | detalle con ownership; OK |
| `/taxpayers/{id}/benefits` | `taxpayerBenefits` / repository | `List<SocialBenefitResponse>` / 200 | R,A,T | beneficios propios; REVIEW-PERF (sin página) |
| `/external-obligations` | `obligations` / filtered query | `Page<ExternalObligationResponse>` / 200 | R,A | obligaciones externas; OK |
| `/external-obligations/errors` | `obligationErrors` / repository | `Page<ExternalObligationResponse>` / 200 | R,A | errores; OK |
| `/external-obligations/{id}` | `obligation` / repository | `ExternalObligationResponse` / 200 | R,A | detalle; OK |
| `/indicators/summary` | `indicatorSummary` / indicator service | `IndicatorSummaryResponse` / 200 | R,S,A | indicadores; REVIEW-PERF (escaneos completos) |
| `/indicators/collection` | `collectionIndicator` / indicator service | `CollectionIndicatorResponse` / 200 | R,S,A | recaudación; REVIEW-PERF (escaneos completos) |
| `/indicators/debt` | `debtIndicator` / indicator service | `DebtIndicatorResponse` / 200 | R,S,A | deuda; REVIEW-PERF (escaneos completos) |
| `/indicators/delinquency` | `delinquencyIndicator` / indicator service | `DelinquencyIndicatorResponse` / 200 | R,S,A | mora; REVIEW-PERF (escaneos completos) |
| `/audit` | `audit` / filtered query | `Page<AuditEntryResponse>` / 200 | S,A | auditoría; OK |
| `/audit/{id}` | `auditEntry` / repository | `AuditEntryResponse` / 200 | S,A | detalle; OK |
| `/audit/entities/{type}/{id}` | `auditEntity` / repository | `List<AuditEntryResponse>` / 200 | S,A | historial; REVIEW-PERF (sin página) |
| `/integrations/events` | `integrations` / filtered query | `Page<IntegrationEventResponse>` / 200 | S,A,Tech | trazas; OK |
| `/integrations/events/errors` | `integrationErrors` / repository | `Page<IntegrationEventResponse>` / 200 | S,Tech | errores/DLQ; OK |
| `/integrations/events/{eventId}` | `integration` / reprocess service | `IntegrationEventResponse` / 200 | S,A,Tech | detalle técnico; OK |
| `/integrations/outbox` | `outbox` / repository | `Page<OutboxEventResponse>` / 200 | S,A,Tech | outbox; OK |

## 7. Inventario completo — POST (59)

| Path | Request → response / HTTP | Roles | Servicio / caso | Estado |
|---|---|---|---|---|
| `/tax-concepts` | `CreateTaxConceptRequest` → `TaxConceptResponse` / 201 | R | catalog / alta | OK |
| `/tax-configurations` | `CreateTaxConfigurationRequest` → `TaxConfigurationResponse` / 201 | R | catalog / borrador | OK |
| `/tax-configurations/{id}/submit` | — → `TaxConfigurationResponse` / 200 | R | catalog / presentar | OK |
| `/tax-configurations/{id}/approve` | `ApprovalRequest?` → `TaxConfigurationResponse` / 200 | S | catalog / aprobar | OK |
| `/tax-configurations/{id}/reject` | `RejectionRequest` → `TaxConfigurationResponse` / 200 | S | catalog / rechazar | OK |
| `/tax-configurations/{id}/deactivate` | `DeactivationRequest` → `TaxConfigurationResponse` / 200 | S | catalog / desactivar | OK |
| `/liquidations/preview` | `LiquidationRequest` → `LiquidationPreview` / 200 | R | liquidation / simular | OK |
| `/liquidations` | `LiquidationRequest` → `LiquidationResponse` / 201 | R | liquidation / emitir | OK |
| `/liquidation-runs` | `CreateLiquidationRunRequest` → `LiquidationRunResponse` / 201 | R | run / crear | OK |
| `/liquidation-runs/{id}/preview` | — → `LiquidationRunDetailResponse` / 200 | R | run / previsualizar | OK |
| `/liquidation-runs/{id}/submit` | — → `LiquidationRunResponse` / 200 | R | run / presentar | OK |
| `/liquidation-runs/{id}/approve` | `ApprovalRequest?` → `LiquidationRunResponse` / 200 | S | run / aprobar | OK |
| `/liquidation-runs/{id}/reject` | `RejectionRequest` → `LiquidationRunResponse` / 200 | S | run / rechazar | OK |
| `/liquidation-runs/{id}/execute` | — → `LiquidationRunResponse` / 200 | R | run / ejecutar | OK |
| `/adjustments` | `CreateAdjustmentRequest` → `AdjustmentResponse` / 201 | R | adjustment / solicitar | OK |
| `/adjustments/{id}/approve` | `ApprovalRequest?` → `AdjustmentResponse` / 200 | S | adjustment / aprobar | OK |
| `/adjustments/{id}/reject` | `RejectionRequest` → `AdjustmentResponse` / 200 | S | adjustment / rechazar | OK |
| `/bills` | `CreateBillRequest` → `BillResponse` / 201 | R | billing / emitir | OK |
| `/payments` | `RegisterPaymentRequest` → `PaymentResponse` / 201 | C | payment / registrar | REVIEW: sin clave HTTP de idempotencia; outbound M7 pendiente |
| `/payments/{id}/allocations` | `AllocationRequest` → `PaymentAllocationResponse` / 200 | R | payment / imputar | OK |
| `/electronic-payments/preview` | `ElectronicPaymentRequest` → `ElectronicPaymentPreview` / 200 | T | electronic payment / simular | OK |
| `/electronic-payments` | `ElectronicPaymentRequest` → `ElectronicPaymentResponse` / 201 | T | electronic payment / iniciar | OK |
| `/credit-balances/{id}/apply` | `ApplyCreditBalanceRequest` → `CreditBalanceApplicationResponse` / 200 | R | credit / aplicar | OK |
| `/payments/{id}/reversal-requests` | `CreateReversalRequest` → `PaymentReversalResponse` / 201 | C | reversal / solicitar | OK |
| `/payment-reversals/{id}/approve` | `ApprovalRequest?` → `PaymentReversalResponse` / 200 | S | reversal / aprobar | OK |
| `/payment-reversals/{id}/reject` | `RejectionRequest` → `PaymentReversalResponse` / 200 | S | reversal / rechazar | OK |
| `/payment-reversals/{id}/execute` | — → `PaymentReversalResponse` / 200 | R | reversal / ejecutar | PENDING_EXTERNAL_CONTRACT sólo para eventual salida M7 |
| `/payment-plan-configurations` | `CreatePaymentPlanConfigurationRequest` → `PaymentPlanConfigurationResponse` / 201 | R | plan workflow / crear | OK |
| `/payment-plans/simulations` | `PaymentPlanSimulationRequest` → `PaymentPlanSimulationResponse` / 200 | R,T | plan workflow / simular | OK |
| `/payment-plan-requests` | `CreatePaymentPlanRequest` → `PaymentPlanRequestResponse` / 201 | T | plan workflow / solicitar | OK |
| `/payment-plan-requests/{id}/grant` | `GrantPaymentPlanRequest?` → `PaymentPlanRequestResponse` / 200 | R | plan workflow / otorgar | OK |
| `/payment-plan-requests/{id}/reject` | `RejectionRequest` → `PaymentPlanRequestResponse` / 200 | R | plan workflow / rechazar | OK |
| `/payment-plan-requests/{id}/submit-exception` | `SubmitPlanExceptionRequest` → `PaymentPlanRequestResponse` / 200 | R | plan workflow / elevar excepción | OK |
| `/payment-plan-requests/{id}/approve-exception` | `ApprovePlanExceptionRequest?` → `PaymentPlanRequestResponse` / 200 | S | plan workflow / aprobar excepción | OK |
| `/payment-plan-requests/{id}/reject-exception` | `RejectionRequest` → `PaymentPlanRequestResponse` / 200 | S | plan workflow / rechazar excepción | OK |
| `/payment-plans/{planId}/expiration-requests` | `CreatePlanExpirationRequest` → `PlanExpirationResponse` / 201 | R | plan workflow / solicitar caducidad | OK |
| `/payment-plan-expirations/{id}/approve` | `ApprovalRequest?` → `PlanExpirationResponse` / 200 | S | plan workflow / aprobar | OK |
| `/payment-plan-expirations/{id}/reject` | `RejectionRequest` → `PlanExpirationResponse` / 200 | S | plan workflow / rechazar | OK |
| `/payment-plans/{planId}/refinancing/simulations` | `RefinancingSimulationRequest` → `RefinancingSimulationResponse` / 200 | R,T | plan workflow / simular | OK |
| `/payment-plans/{planId}/refinancing-requests` | `CreateRefinancingRequest` → `RefinancingRequestResponse` / 201 | R,T | plan workflow / solicitar | OK |
| `/refinancing-requests/{id}/grant` | — → `RefinancingRequestResponse` / 200 | R | plan workflow / otorgar | OK |
| `/refinancing-requests/{id}/reject` | `RejectionRequest` → `RefinancingRequestResponse` / 200 | R | plan workflow / rechazar | OK |
| `/refinancing-requests/{id}/submit-exception` | `SubmitPlanExceptionRequest` → `RefinancingRequestResponse` / 200 | R | plan workflow / elevar excepción | OK |
| `/refinancing-requests/{id}/approve-exception` | `ApprovalRequest?` → `RefinancingRequestResponse` / 200 | S | plan workflow / aprobar excepción | OK |
| `/refinancing-requests/{id}/reject-exception` | `RejectionRequest` → `RefinancingRequestResponse` / 200 | S | plan workflow / rechazar excepción | OK |
| `/exemption-requests` | `CreateExemptionRequest` → `ExemptionRequestResponse` / 201 | T | exemption / solicitar con ownership | OK |
| `/exemption-requests/{id}/start-review` | — → `ExemptionRequestResponse` / 200 | R | exemption / iniciar revisión | OK |
| `/exemption-requests/{id}/request-documentation` | `RequestDocumentationRequest` → `ExemptionRequestResponse` / 200 | R | exemption / pedir documentos | OK |
| `/exemption-requests/{id}/documentation` | `SubmitDocumentationRequest` → `ExemptionRequestResponse` / 200 | T | exemption / aportar con ownership | OK |
| `/exemption-requests/{id}/submit-resolution` | `SubmitExemptionResolutionRequest?` → `ExemptionRequestResponse` / 200 | R | exemption / elevar resolución | OK |
| `/exemption-requests/{id}/approve` | `ApproveExemptionRequest?` → `ExemptionResponse` / 200 | S | exemption / aprobar | OK |
| `/exemption-requests/{id}/reject` | `RejectionRequest` → `ExemptionRequestResponse` / 200 | S | exemption / rechazar | OK |
| `/tickets/{id}/assign` | — → `TicketResponse` / 200 | R | ticket / asignar | OK |
| `/tickets/{id}/updates` | `CreateTicketUpdateRequest` → `TicketResponse` / 200 | R | ticket / nota | OK |
| `/tickets/{id}/request-information` | `TicketInformationRequest` → `TicketResponse` / 200 | R | ticket / pedir información | OK |
| `/tickets/{id}/complete` | `CompleteTicketRequest` → `TicketResponse` / 200 | R | ticket / completar | OK |
| `/tickets/{id}/reject` | `RejectionRequest` → `TicketResponse` / 200 | R | ticket / rechazar | OK |
| `/external-obligations/{id}/retry` | — → `ExternalObligationResponse` / 200 | R | external obligation / reintentar | OK |
| `/integrations/events/{eventId}/reprocess` | `ReprocessEventRequest?` → `IntegrationEventResponse` / 200 | S,Tech | integración / reproceso técnico | OK |

## 8. Inventario completo — PATCH (3)

| Path | Request → response / HTTP | Roles | Servicio / caso | Estado |
|---|---|---|---|---|
| `/tax-concepts/{id}` | `UpdateTaxConceptRequest` → `TaxConceptResponse` / 200 | R | catalog / edición parcial | OK |
| `/tax-configurations/{id}` | `UpdateTaxConfigurationRequest` → `TaxConfigurationResponse` / 200 | R | catalog / sólo DRAFT | OK |
| `/payment-plan-configurations/{id}` | `UpdatePaymentPlanConfigurationRequest` → `PaymentPlanConfigurationResponse` / 200 | R | plan workflow / edición parcial | OK |

## 9. Hallazgos y decisiones

### Corregidos

- DTO y consumer dedicado al envelope real de M7.
- Resolución DNI/CUIT sin confundir identificador externo con PK local.
- `finalAmount` como único importe económico.
- Idempotencia concurrente reforzada en base de datos.
- Reproceso del payload M7 exacto y compatibilidad explícita con logs legacy.
- Test OpenAPI de 122 paths/136 operaciones y ownership HTTP de boleta concreta.
- Tests Flyway actualizados a V11.

### No bloqueantes, para backlog

- Evitar N+1 en respuestas de liquidaciones, deudas y boletas mediante queries/proyecciones dedicadas.
- Reemplazar escaneos completos de indicadores y planes caídos por agregaciones/paginación en repositorio.
- Evaluar paginación para colecciones hijas que puedan crecer.
- Definir idempotencia de comandos HTTP de pago con el equipo antes de agregar headers o claves.
- Uniformar `Location` en altas 201 si se adopta como convención API del equipo.

## 10. Pruebas específicas M7

`M7ContractTests` cubre payload documental exacto, uso de `finalAmount`, normalización, mismo `eventId`, distinto `eventId` con igual `infractionId`, fuente inválida, validación sin efecto parcial, DNI/CUIT, contribuyente ausente y reproceso posterior. `M7ConcurrencyTests` dispara dos eventos concurrentes para la misma infracción. Los tests de dominio legacy se mantienen para no romper compatibilidad interna.

| Validación | Resultado local |
|---|---|
| `mvnw.cmd clean verify` | BUILD SUCCESS |
| Tests | 83 detectados; 78 ejecutados; 0 failures; 0 errors; 5 Testcontainers omitidos |
| JaCoCo líneas | 88,84%; umbral 85% cumplido |
| Flyway sobre H2 de compatibilidad | 11 migraciones validadas y aplicadas hasta V11 |
| OpenAPI con MockMvc | 122 paths; 136 operaciones; sin `/api/v1/events*` |
| Docker daemon | no disponible (`dockerDesktopLinuxEngine` inexistente) |
| Compose | no iniciado; requiere además `POSTGRES_PASSWORD` local, que no se inventó |
| PostgreSQL real | pendiente: los 5 tests Testcontainers quedaron omitidos por indisponibilidad de Docker |

La excepción que aparece en el log de `BoundaryHardeningTests` es el estímulo intencional de un test del handler; esa clase terminó con 2/2 tests aprobados y sin filtración de detalle al response.

## 11. Pendientes declarados

- M4: **Pendiente contrato real M4; no se inventaron cambios.** Estado `PENDING_M4_CONTRACT`.
- M7 outbound: `PENDING_M7_CONTRACT` hasta confirmar nombre, payload, versionado, destino, semántica, ACK, retry y DLQ.
- Core/JWT: pendiente contrato real; se mantiene la abstracción local testeable.
- Broker: pendiente definición tecnológica y operativa; el contrato se prueba directamente sin transporte.

## 12. Variables de entorno / secretos

Sin cambios en variables de entorno ni secretos.

## 13. Archivos modificados por esta adaptación

- `src/main/java/ar/gob/municipalidad/rentas/integration/event/inbound/ConfirmedInboundEvents.java`
- `src/main/java/ar/gob/municipalidad/rentas/integration/event/inbound/ConfirmedIntegrationConsumers.java`
- `src/main/java/ar/gob/municipalidad/rentas/IntegrationServices.java`
- `src/main/java/ar/gob/municipalidad/rentas/QueryServices.java`
- `src/main/java/ar/gob/municipalidad/rentas/Repositories.java`
- `src/main/resources/db/migration/V11__m7_infraction_integrity.sql`
- `src/test/java/ar/gob/municipalidad/rentas/M7ContractTests.java`
- `src/test/java/ar/gob/municipalidad/rentas/M7ConcurrencyTests.java`
- `src/test/java/ar/gob/municipalidad/rentas/SecurityTests.java`
- `src/test/java/ar/gob/municipalidad/rentas/MigrationValidationTest.java`
- `src/test/java/ar/gob/municipalidad/rentas/PostgreSqlIntegrationTest.java`
- `INTEGRATION_CONTRACTS.md`
- `README.md`
- `M7_CONTRACT_AND_API_AUDIT.md`

Fuera del repositorio se creó una guía de aprendizaje local, que no forma parte de los archivos versionables. El working tree ya contenía otros cambios de hardening y auditoría de entorno anteriores; se preservaron y no se atribuyen a esta adaptación.
