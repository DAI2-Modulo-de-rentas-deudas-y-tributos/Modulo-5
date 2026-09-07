# Estado de completitud â€” MÃ³dulo 5 Rentas

Fuente revisada: Matriz Maestra REST y secciones de diseño del PDF de alcance. `COMPLETE` exige contrato, DTO, validación, autorización, persistencia, errores, OpenAPI y pruebas razonables. `BLOCKED_EXTERNAL` se reserva para contratos de otros equipos que M5 no debe inventar. `MISSING` indica ausencia local.

## Matriz REST

| Funcionalidad/HU | Endpoint | Controller | Service | Persistencia | Test | Estado |
|---|---|---|---|---|---|---|
| AutenticaciÃ³n/Core | POST /auth/login | â€” | â€” | â€” | â€” | BLOCKED_EXTERNAL |
| AutenticaciÃ³n/Core | POST /auth/logout | â€” | â€” | â€” | â€” | BLOCKED_EXTERNAL |
| AutenticaciÃ³n/Core | GET /auth/me | â€” | `CurrentIdentity` adapter | â€” | `SecurityTests` | BLOCKED_EXTERNAL |
| Contribuyentes HU-01 | GET /taxpayers | `ApiController` | â€” | `TaxpayerRepository` | contexto | COMPLETE |
| Contribuyentes HU-02 | GET /taxpayers/{taxpayerId} | `ApiController` | ownership | `TaxpayerRepository` | `SecurityTests` | COMPLETE |
| Contribuyentes HU-02/HU-07 | GET /taxpayers/{taxpayerId}/summary | `ApiController` | `TaxpayerQueryService` | consultas M5 | `IndicatorTests` | COMPLETE |
| Beneficios HU-27 | GET /taxpayers/{taxpayerId}/benefits | `ApiController` | ownership | `SocialBenefitRepository` | `IntegrationFlowTests` | COMPLETE |
| Conceptos HU-22 | GET /tax-concepts | `ApiController` | â€” | `TaxConceptRepository` | contexto | COMPLETE |
| Conceptos HU-22 | GET /tax-concepts/{conceptId} | `ApiController` | â€” | `TaxConceptRepository` | contexto | COMPLETE |
| Conceptos HU-22 | POST /tax-concepts | `ApiController` | `CatalogService` | `tax_concept` | flujos | COMPLETE |
| Conceptos HU-22 | PATCH /tax-concepts/{conceptId} | `ApiController` | `CatalogService` | `tax_concept` | contexto | COMPLETE |
| ConfiguraciÃ³n HU-22 | GET /tax-configurations | `ApiController` | â€” | repositorio | contexto | COMPLETE |
| ConfiguraciÃ³n HU-22 | GET /tax-configurations/{configurationId} | `ApiController` | â€” | repositorio | contexto | COMPLETE |
| ConfiguraciÃ³n HU-22 | POST /tax-configurations | `ApiController` | `CatalogService` | `tax_configuration` | `DomainFlowTests` | COMPLETE |
| ConfiguraciÃ³n HU-22 | PATCH /tax-configurations/{configurationId} | `ApiController` | `CatalogService` | `tax_configuration` | contexto | COMPLETE |
| ConfiguraciÃ³n HU-22 | POST /tax-configurations/{id}/submit | `ApiController` | `CatalogService` | estado | flujos | COMPLETE |
| ConfiguraciÃ³n HU-S05 | POST /tax-configurations/{id}/approve | `ApiController` | `CatalogService` | estado/audit | flujos | COMPLETE |
| ConfiguraciÃ³n HU-S05 | POST /tax-configurations/{id}/reject | `ApiController` | `CatalogService` | estado | contexto | COMPLETE |
| ConfiguraciÃ³n HU-S05 | POST /tax-configurations/{id}/deactivate | `ApiController` | `CatalogService` | estado/audit | contexto | COMPLETE |
| LiquidaciÃ³n HU-03 | GET /liquidations | `ApiController` | â€” | repositorio | contexto | COMPLETE |
| LiquidaciÃ³n HU-03 | GET /liquidations/{liquidationId} | `ApiController` | ownership | repositorio | flujos | COMPLETE |
| LiquidaciÃ³n HU-03 | POST /liquidations/preview | `ApiController` | `LiquidationService` | sin mutaciÃ³n | `DomainFlowTests` | COMPLETE |
| LiquidaciÃ³n HU-03 | POST /liquidations | `ApiController` | `LiquidationService` | liquidation/debt | `DomainFlowTests` | COMPLETE |
| Corridas HU-05 | GET /liquidation-runs | `ApiController` | â€” | repositorio | contexto | COMPLETE |
| Corridas HU-05 | GET /liquidation-runs/{runId} | `ApiController` | `LiquidationRunService` | run/items | `AdjustmentRunFlowTests` | COMPLETE |
| Corridas HU-05 | POST /liquidation-runs | `ApiController` | `LiquidationRunService` | run/items | `AdjustmentRunFlowTests` | COMPLETE |
| Corridas HU-05 | POST /liquidation-runs/{runId}/preview | `ApiController` | `LiquidationRunService` | resumen/items | `AdjustmentRunFlowTests` | COMPLETE |
| Corridas HU-05 | POST /liquidation-runs/{runId}/submit | `ApiController` | `LiquidationRunService` | estado | `AdjustmentRunFlowTests` | COMPLETE |
| Corridas HU-S06 | POST /liquidation-runs/{runId}/approve | `ApiController` | `LiquidationRunService` | estado/audit | `AdjustmentRunFlowTests` | COMPLETE |
| Corridas HU-S06 | POST /liquidation-runs/{runId}/reject | `ApiController` | `LiquidationRunService` | estado | contexto | COMPLETE |
| Corridas (flujo de diseño 22.2; ausente en matriz maestra) | POST /liquidation-runs/{runId}/execute | `ApiController` | `LiquidationRunService` | liquidation/debt/items | `AdjustmentRunFlowTests` | IMPLEMENTED_NOT_DOCUMENTED |
| Ajustes HU-06 | GET /adjustments | `ApiController` | â€” | repositorio | contexto | COMPLETE |
| Ajustes HU-06 | GET /adjustments/{adjustmentId} | `ApiController` | `AdjustmentService` | adjustment | pruebas de flujo | COMPLETE |
| Ajustes HU-06 | POST /adjustments | `ApiController` | `AdjustmentService` | adjustment/audit | `AdjustmentRunFlowTests` | COMPLETE |
| Ajustes HU-S01 | POST /adjustments/{id}/approve | `ApiController` | `AdjustmentService` | debt/adjustment/outbox | `AdjustmentRunFlowTests` | COMPLETE |
| Ajustes HU-S01 | POST /adjustments/{id}/reject | `ApiController` | `AdjustmentService` | estado | contexto | COMPLETE |
| Deudas HU-07 | GET /debts | `ApiController` | â€” | repositorio | contexto | COMPLETE |
| Deudas HU-07 | GET /debts/{debtId} | `ApiController` | ownership | repositorio | flujos/security | COMPLETE |
| Deudas HU-07 | GET /taxpayers/{taxpayerId}/debts | `ApiController` | ownership | repositorio | flujos | COMPLETE |
| Deudas HU-07 | GET /taxpayers/{taxpayerId}/debts/summary | `ApiController` | `TaxpayerQueryService` | agregaciÃ³n real | `IndicatorTests` | COMPLETE |
| Boletas HU-C01 | GET /bills | `ApiController` | â€” | repositorio | contexto | COMPLETE |
| Boletas HU-C01 | GET /bills/{billId} | `ApiController` | `BillingService` | bill/bill_debt | `BillingFlowTests` | COMPLETE |
| Boletas HU-C01 | GET /taxpayers/{taxpayerId}/bills | `ApiController` | ownership | repositorio | `BillingFlowTests` | COMPLETE |
| Boletas HU-C01 | POST /bills | `ApiController` | `BillingService` | bill/bill_debt | `BillingFlowTests` | COMPLETE |
| Boletas HU-C01 | GET /bills/{billId}/document | `ApiController` | `PdfDocumentService` | lectura | `BillingFlowTests` | COMPLETE |
| Pagos HU-09 | GET /payments | `ApiController` | â€” | repositorio | contexto | COMPLETE |
| Pagos HU-09 | GET /payments/{paymentId} | `ApiController` | ownership | repositorio | flujos | COMPLETE |
| Pagos HU-09 | POST /payments | `ApiController` | `PaymentService` | payment/allocation/debt/outbox | mÃºltiples | COMPLETE |
| Pagos HU-CON02 | GET /taxpayers/{taxpayerId}/payments | `ApiController` | ownership | repositorio | contexto | COMPLETE |
| Pagos HU-09 | GET /payments/{paymentId}/receipt | `ApiController` | proyecciÃ³n | payment | contexto | COMPLETE |
| Pago electrÃ³nico | POST /electronic-payments/preview | `ApiController` | `ElectronicPaymentService` | sin mutaciÃ³n | `BillingFlowTests` | COMPLETE |
| Pago electrÃ³nico | POST /electronic-payments | `ApiController` | `ElectronicPaymentService` | attempt/payment | `BillingFlowTests` | COMPLETE |
| Pago electrÃ³nico | GET /electronic-payments/{paymentId} | `ApiController` | `ElectronicPaymentService` | attempt | `BillingFlowTests` | COMPLETE |
| Imputaciones HU-10 | GET /payment-allocations | `ApiController` | â€” | repositorio | flujos | COMPLETE |
| Imputaciones HU-10 | GET /payments/{paymentId}/allocations | `ApiController` | â€” | repositorio | flujos | COMPLETE |
| Imputaciones HU-10 | GET /payments/unallocated | `ApiController` | â€” | consulta paginada | `BillingFlowTests` | COMPLETE |
| Imputaciones HU-10 | POST /payments/{paymentId}/allocations | `ApiController` | `PaymentService` | debt o installment | billing/planes | COMPLETE |
| Saldos HU-11 | GET /credit-balances | `ApiController` | â€” | repositorio | contexto | COMPLETE |
| Saldos HU-11 | GET /credit-balances/{creditBalanceId} | `ApiController` | ownership | repositorio | billing | COMPLETE |
| Saldos HU-11 | GET /taxpayers/{taxpayerId}/credit-balances | `ApiController` | ownership | repositorio | billing | COMPLETE |
| Saldos HU-11 | POST /credit-balances/{id}/apply | `ApiController` | `CreditBalanceService` | credit/debt/audit | `BillingFlowTests` | COMPLETE |
| Reversiones HU-12 | GET /payment-reversals | `ApiController` | â€” | repositorio | contexto | COMPLETE |
| Reversiones HU-12 | GET /payment-reversals/{reversalId} | `ApiController` | â€” | repositorio | flujos | COMPLETE |
| Reversiones HU-12 | POST /payments/{paymentId}/reversal-requests | `ApiController` | `ReversalService` | reversal/audit | `DomainFlowTests` | COMPLETE |
| Reversiones HU-S02 | POST /payment-reversals/{id}/approve | `ApiController` | `ReversalService` | estado/audit | `DomainFlowTests` | COMPLETE |
| Reversiones HU-S02 | POST /payment-reversals/{id}/reject | `ApiController` | `ReversalService` | estado | contexto | COMPLETE |
| Reversiones HU-12 | POST /payment-reversals/{id}/execute | `ApiController` | `ReversalService` | payment/debt/plan/credit | `DomainFlowTests` | COMPLETE |
| Config planes HU-23 | GET /payment-plan-configurations | `ApiController` | â€” | repositorio | planes | COMPLETE |
| Config planes HU-23 | GET /payment-plan-configurations/{id} | `ApiController` | â€” | repositorio | planes | COMPLETE |
| Config planes HU-23 | POST /payment-plan-configurations | `ApiController` | `PlanWorkflowService` | configuraciÃ³n versionada | `PaymentPlanFlowTests` | COMPLETE |
| Config planes HU-23 | PATCH /payment-plan-configurations/{id} | `ApiController` | `PlanWorkflowService` | configuraciÃ³n | planes | COMPLETE |
| Planes HU-13 | POST /payment-plans/simulations | `ApiController` | `PlanWorkflowService` | sin mutaciÃ³n | `PaymentPlanFlowTests` | COMPLETE |
| Solicitudes HU-14 | GET /payment-plan-requests | `ApiController` | â€” | repositorio | planes | COMPLETE |
| Solicitudes HU-14 | GET /payment-plan-requests/{requestId} | `ApiController` | ownership | repositorio | planes | COMPLETE |
| Solicitudes HU-CON03 | GET /taxpayers/{taxpayerId}/payment-plan-requests | `ApiController` | ownership | repositorio | planes | COMPLETE |
| Solicitudes HU-CON03 | POST /payment-plan-requests | `ApiController` | `PlanWorkflowService` | request/debts | `PaymentPlanFlowTests` | COMPLETE |
| Solicitudes HU-14 | POST /payment-plan-requests/{id}/grant | `ApiController` | `PlanWorkflowService` | plan/debts/installments/outbox | `PaymentPlanFlowTests` | COMPLETE |
| Solicitudes HU-14 | POST /payment-plan-requests/{id}/reject | `ApiController` | `PlanWorkflowService` | estado | planes | COMPLETE |
| ExcepciÃ³n HU-14 | POST /payment-plan-requests/{id}/submit-exception | `ApiController` | `PlanWorkflowService` | estado | `PaymentPlanFlowTests` | COMPLETE |
| ExcepciÃ³n HU-S08 | POST /payment-plan-requests/{id}/approve-exception | `ApiController` | `PlanWorkflowService` | estado | `PaymentPlanFlowTests` | COMPLETE |
| ExcepciÃ³n HU-S08 | POST /payment-plan-requests/{id}/reject-exception | `ApiController` | `PlanWorkflowService` | estado | planes | COMPLETE |
| Planes HU-15 | GET /payment-plans | `ApiController` | â€” | repositorio | planes | COMPLETE |
| Planes HU-15 | GET /payment-plans/{planId} | `ApiController` | ownership | repositorio | planes | COMPLETE |
| Planes HU-15 | GET /taxpayers/{taxpayerId}/payment-plans | `ApiController` | ownership | repositorio | planes | COMPLETE |
| Cuotas HU-15 | GET /payment-plans/{planId}/installments | `ApiController` | ownership | installment | planes | COMPLETE |
| Cuotas HU-15 | GET /payment-plans/{planId}/installments/{installmentId} | `ApiController` | `PlanWorkflowService` | installment | planes | COMPLETE |
| Incumplimiento HU-25 | GET /payment-plans/defaulted | `ApiController` | cÃ¡lculo derivado | installment/config | `PaymentPlanFlowTests` | COMPLETE |
| Caducidad HU-25 | GET /payment-plan-expirations | `ApiController` | â€” | repositorio | planes | COMPLETE |
| Caducidad HU-25 | GET /payment-plan-expirations/{id} | `ApiController` | `PlanWorkflowService` | repositorio | planes | COMPLETE |
| Caducidad HU-25 | POST /payment-plans/{planId}/expiration-requests | `ApiController` | `PlanWorkflowService` | expiration | `PaymentPlanFlowTests` | COMPLETE |
| Caducidad HU-S09 | POST /payment-plan-expirations/{id}/approve | `ApiController` | `PlanWorkflowService` | plan/installments/debts/outbox | `PaymentPlanFlowTests` | COMPLETE |
| Caducidad HU-S09 | POST /payment-plan-expirations/{id}/reject | `ApiController` | `PlanWorkflowService` | estado | planes | COMPLETE |
| RefinanciaciÃ³n HU-16 | POST /payment-plans/{planId}/refinancing/simulations | `ApiController` | `PlanWorkflowService` | sin mutaciÃ³n | planes | COMPLETE |
| RefinanciaciÃ³n HU-16 | POST /payment-plans/{planId}/refinancing-requests | `ApiController` | `PlanWorkflowService` | request | `PaymentPlanFlowTests` | COMPLETE |
| RefinanciaciÃ³n HU-16 | GET /refinancing-requests | `ApiController` | â€” | repositorio | planes | COMPLETE |
| RefinanciaciÃ³n HU-16 | GET /refinancing-requests/{id} | `ApiController` | ownership | repositorio | planes | COMPLETE |
| RefinanciaciÃ³n HU-16 | POST /refinancing-requests/{id}/grant | `ApiController` | `PlanWorkflowService` | plan histÃ³rico/nuevo | `PaymentPlanFlowTests` | COMPLETE |
| RefinanciaciÃ³n HU-16 | POST /refinancing-requests/{id}/reject | `ApiController` | `PlanWorkflowService` | estado | planes | COMPLETE |
| RefinanciaciÃ³n HU-16 | POST /refinancing-requests/{id}/submit-exception | `ApiController` | `PlanWorkflowService` | estado | planes | COMPLETE |
| RefinanciaciÃ³n HU-S10 | POST /refinancing-requests/{id}/approve-exception | `ApiController` | `PlanWorkflowService` | estado | planes | COMPLETE |
| RefinanciaciÃ³n HU-S10 | POST /refinancing-requests/{id}/reject-exception | `ApiController` | `PlanWorkflowService` | estado | planes | COMPLETE |
| Exenciones HU-18 | GET /exemption-requests | `ApiController` | â€” | repositorio | flujos | COMPLETE |
| Exenciones HU-17 | GET /exemption-requests/{requestId} | `ApiController` | ownership | repositorio | flujos | COMPLETE |
| Exenciones HU-17 | GET /taxpayers/{taxpayerId}/exemption-requests | `ApiController` | ownership | repositorio | flujos | COMPLETE |
| Exenciones HU-17 | POST /exemption-requests | `ApiController` | `ExemptionService` | request | `DomainFlowTests` | COMPLETE |
| Exenciones HU-18 | POST /exemption-requests/{id}/start-review | `ApiController` | `ExemptionService` | estado/metadatos | flujos | COMPLETE |
| Exenciones HU-18 | POST /exemption-requests/{id}/request-documentation | `ApiController` | `ExemptionService` | estado | contexto | COMPLETE |
| Exenciones HU-17 | POST /exemption-requests/{id}/documentation | `ApiController` | `ExemptionService` | document reference | contexto | COMPLETE |
| Exenciones HU-18 | POST /exemption-requests/{id}/submit-resolution | `ApiController` | `ExemptionService` | estado/metadatos | flujos | COMPLETE |
| Exenciones HU-S03 | POST /exemption-requests/{id}/approve | `ApiController` | `ExemptionService` | exemption/audit | `DomainFlowTests` | COMPLETE |
| Exenciones HU-S03 | POST /exemption-requests/{id}/reject | `ApiController` | `ExemptionService` | estado/audit | contexto | COMPLETE |
| Exenciones HU-S03 | GET /exemptions | `ApiController` | â€” | repositorio | flujos | COMPLETE |
| Exenciones HU-17 | GET /exemptions/{exemptionId} | `ApiController` | ownership | repositorio | flujos | COMPLETE |
| Exenciones HU-17 | GET /taxpayers/{taxpayerId}/exemptions | `ApiController` | ownership | repositorio | flujos | COMPLETE |
| Tickets HU-19 | GET /tickets | `ApiController` | â€” | ticket_case | integraciÃ³n | COMPLETE |
| Tickets HU-19 | GET /tickets/{ticketId} | `ApiController` | `TicketService` | ticket_case | integraciÃ³n | COMPLETE |
| Tickets HU-19 | POST /tickets/{ticketId}/assign | `ApiController` | `TicketService` | ticket/outbox | `IntegrationFlowTests` | COMPLETE |
| Tickets HU-20 | POST /tickets/{ticketId}/updates | `ApiController` | `TicketService` | ticket_update/audit | integraciÃ³n | COMPLETE |
| Tickets HU-20 | POST /tickets/{ticketId}/request-information | `ApiController` | `TicketService` | update/outbox | integraciÃ³n | COMPLETE |
| Tickets HU-20 | POST /tickets/{ticketId}/complete | `ApiController` | `TicketService` | update/outbox | `IntegrationFlowTests` | COMPLETE |
| Tickets HU-20 | POST /tickets/{ticketId}/reject | `ApiController` | `TicketService` | update/outbox | integraciÃ³n | COMPLETE |
| Beneficios HU-27 | GET /social-benefits | `ApiController` | â€” | benefit/links | integraciÃ³n | COMPLETE |
| Beneficios HU-27 | GET /social-benefits/{benefitId} | `ApiController` | ownership | benefit | integraciÃ³n | COMPLETE |
| Obligaciones HU-26 | GET /external-obligations | `ApiController` | â€” | repositorio | integraciÃ³n | COMPLETE |
| Obligaciones HU-26 | GET /external-obligations/{id} | `ApiController` | â€” | repositorio | integraciÃ³n | COMPLETE |
| Obligaciones HU-26 | GET /external-obligations/errors | `ApiController` | â€” | consulta por estado | `IntegrationFlowTests` | COMPLETE |
| Obligaciones HU-26 | POST /external-obligations/{id}/retry | `ApiController` | `ExternalObligationService` | obligation/debt/log | `IntegrationFlowTests` | COMPLETE |
| Indicadores HU-21 | GET /indicators/summary | `ApiController` | `IndicatorService` | agregaciÃ³n real | `IndicatorTests` | COMPLETE |
| Indicadores HU-21 | GET /indicators/collection | `ApiController` | `IndicatorService` | payment real | `IndicatorTests` | COMPLETE |
| Indicadores HU-21 | GET /indicators/debt | `ApiController` | `IndicatorService` | debt real | `IndicatorTests` | COMPLETE |
| Indicadores HU-21 | GET /indicators/delinquency | `ApiController` | `IndicatorService` | fecha/saldo real | `IndicatorTests` | COMPLETE |
| AuditorÃ­a HU-A | GET /audit | `ApiController` | â€” | audit_entry | flujos | COMPLETE |
| AuditorÃ­a HU-A | GET /audit/{auditId} | `ApiController` | â€” | audit_entry | flujos | COMPLETE |
| AuditorÃ­a HU-A | GET /audit/entities/{entityType}/{entityId} | `ApiController` | â€” | consulta indexable | flujos | COMPLETE |
| IntegraciÃ³n DoD | GET /integrations/events | `ApiController` | â€” | event_log | integraciÃ³n | COMPLETE |
| IntegraciÃ³n DoD | GET /integrations/events/{eventId} | `ApiController` | `IntegrationReprocessService` | event_log | `IntegrationFlowTests` | COMPLETE |
| IntegraciÃ³n DoD | GET /integrations/events/errors | `ApiController` | â€” | consulta por estado | integraciÃ³n | COMPLETE |
| IntegraciÃ³n HU-26 | POST /integrations/events/{eventId}/reprocess | `ApiController` | `IntegrationReprocessService` | log + handler original | `IntegrationFlowTests` | COMPLETE |

Los 25 listados generales usan `FilteredQueryService` y repositorios con `JpaSpecificationExecutor`: aceptan campos permitidos por recurso, búsqueda cuando corresponde, rangos `from/to`, paginación y sorting, y rechazan filtros u órdenes desconocidos con error 400 estable. Los endpoints de liquidación persisten `LiquidationComponent` con fuente y desglose, y todos los contratos principales devuelven Response DTO en lugar de entidades JPA.

## OPENAPI_CONTRACT_REPORT

| Clasificación | Cantidad | Detalle |
|---|---:|---|
| DOCUMENTED_AND_IMPLEMENTED | 134 | Operaciones de la matriz maestra presentes bajo `/api/v1` |
| DOCUMENTED_PARTIAL | 0 | — |
| DOCUMENTED_MISSING | 0 | — |
| BLOCKED_EXTERNAL | 3 | `POST /auth/login`, `POST /auth/logout`, `GET /auth/me`; M5 sólo validará JWT/Core |
| IMPLEMENTED_NOT_DOCUMENTED | 2 | `POST /liquidation-runs/{runId}/execute` (justificado por flujo 22.2) y `GET /integrations/outbox` (consulta técnica) |

La matriz maestra contiene 137 operaciones: 134 locales y 3 de autenticación delegadas a Core. OpenAPI expone 136 operaciones de M5: las 134 documentadas locales y los dos extras justificados. No existen rutas `/events/*`.

## Resumen final REST

| Métrica | Cantidad |
|---|---:|
| TOTAL DOCUMENTED ENDPOINTS | 137 |
| COMPLETE | 134 |
| PARTIAL | 0 |
| MISSING | 0 |
| BLOCKED_EXTERNAL | 3 |
| TECHNICAL EXTRA ENDPOINTS | 1 |
| FUNCTIONAL DESIGN EXTRA PENDING CONTRACT REVIEW | 1 |

## Verificación final local - 2026-08-25

| Control | Resultado |
|---|---|
| `mvnw clean verify` | BUILD SUCCESS |
| Tests detectados / aprobados | 45 / 42 |
| Fallos / errores | 0 / 0 |
| Testcontainers PostgreSQL | 3 omitidos limpiamente: Docker no está disponible en esta PC |
| Flyway H2 PostgreSQL mode | 8 migraciones validadas y aplicadas |
| Cobertura JaCoCo de líneas total | 470 / 728 = 64,56 % |
| Arranque HTTP local controlado | exitoso con H2 de test |
| `/actuator/health` | HTTP 200, `UP` |
| `/swagger-ui.html` | HTTP 200 |
| `/v3/api-docs` | HTTP 200, 122 paths, 136 operaciones |
| Rutas prohibidas `/api/v1/events*` | 0 |
| Arranque normal PostgreSQL | no validado: conexión `localhost:5432` rechazada y Docker ausente |

Los tres tests PostgreSQL no se contabilizan como aprobados. Deben ejecutarse nuevamente con Docker Desktop activo antes de una entrega que exija evidencia sobre PostgreSQL real.

## EVENT_CONTRACT_STATUS

| Event | Direction | Producer | Consumer | Handler | Outbox | Contract status |
|---|---|---|---|---|---|---|
| taxpayerCreated / taxpayerUpdated | INBOUND | M1 | adapter de broker pendiente | `TaxpayerIntegrationService` idempotente | â€” | BLOCKED_EXTERNAL |
| permitFeeGenerated | INBOUND | M4 | adapter de broker pendiente | `ExternalObligationService.consumePermitFee` | â€” | LOCAL_ADAPTER_ONLY |
| commercialFineGenerated | INBOUND | M4 | adapter de broker pendiente | `ExternalObligationService.consumeCommercialFine` | â€” | LOCAL_ADAPTER_ONLY |
| infractionConfirmed | INBOUND | M7 | adapter de broker pendiente | `ExternalObligationService.consumeInfraction` | â€” | LOCAL_ADAPTER_ONLY |
| ticketCreated | INBOUND | M2 | adapter de broker pendiente | `TicketIntegrationService.consume` | â€” | LOCAL_ADAPTER_ONLY |
| ticketUpdated | INBOUND | M2 | adapter de broker pendiente | `TicketIntegrationService.consume` | â€” | BLOCKED_EXTERNAL |
| socialBenefitUpdated | INBOUND | M8 | adapter de broker pendiente | `SocialBenefitIntegrationService.consume` | â€” | BLOCKED_EXTERNAL |
| paymentRegistered | OUTBOUND | M5 | â€” | local Outbox publisher | sÃ­, destino PLATFORM | LOCAL_ADAPTER_ONLY |
| debtSettled | OUTBOUND | M5 | â€” | local Outbox publisher | sÃ­, destino PLATFORM | LOCAL_ADAPTER_ONLY |
| debtAdjusted | OUTBOUND | M5 | â€” | local Outbox publisher | sÃ­, destino PLATFORM | LOCAL_ADAPTER_ONLY |
| paymentPlanGranted | OUTBOUND | M5 | â€” | local Outbox publisher | sÃ­, destino PLATFORM | LOCAL_ADAPTER_ONLY |
| paymentPlanExpired | OUTBOUND | M5 | â€” | local Outbox publisher | sÃ­, destino PLATFORM | LOCAL_ADAPTER_ONLY |
| paymentPlanRefinanced | OUTBOUND | M5 | â€” | local Outbox publisher | sÃ­, destino PLATFORM | LOCAL_ADAPTER_ONLY |
| paymentPlanCompleted | OUTBOUND | M5 | â€” | local Outbox publisher | sÃ­, destino PLATFORM | LOCAL_ADAPTER_ONLY |
| updateTicketStatus | OUTBOUND | M5 | â€” | local Outbox publisher | sÃ­, destino M2 | BLOCKED_EXTERNAL |

`BLOCKED_EXTERNAL` significa que el PDF declara que el contrato definitivo del mÃ³dulo externo sigue pendiente. Los handlers locales aceptan la forma v1 desacoplada, pero no se declara integraciÃ³n real hasta acordar schema, tÃ³pico/cola, ACK, retries y DLQ. No se crearon endpoints `/events/*`, tal como exige el PDF.
