# Estado verificado del traspaso PO — P0/P1

**Verificado:** 26/10/2026
**Base:** `integration/m5-po-aligned-20261026` (`515fe16119ec77a849a6162cae55783322319d4d` antes de esta rama).
**PR de integración #67:** OPEN, Draft, mergeable; CI verde. Todavía no está incorporado a `develop`.

Este archivo separa reportes históricos fechados el 25/10 de lo verificado en código y pruebas. No representa aceptación de M4/Core ni entrega efectiva de eventos.

Estados usados: **IMPLEMENTADO** = código presente; **PROBADO LOCALMENTE** = verificado por pruebas locales; **ACEPTADO POR DESTINO** = el módulo receptor confirmó aceptación; **ENTREGADO POR CORE** = existe evidencia de entrega/ACK del transporte Core; **PENDIENTE** = falta implementación, decisión o evidencia. Las dos últimas categorías externas no se infieren a partir de un outbox local.

## P0 — documentación disponible

- Los Markdown del paquete PO están en esta carpeta, incluidos `AGENTS.md`, `06_UUID_SALIENTES_ACTUAL_VS_OBJETIVO.md` y `07_ENTRANTES_SALIENTES_CORE_Y_DISTRIBUCION.md`.
- Los contratos originales M2, M4, M5, M7 y M8 están en `contratos_originales/`; las propuestas permanecen separadas en `propuestas_no_ratificadas/`.
- No se copió `M1_Catalogo_Eventos_v2.1.docx`: la copia recibida contiene ejemplos con identificadores personales y cadenas con forma de credenciales. Se requiere una versión saneada del PO.
- Se sustituyeron en copias Markdown los ejemplos CUIT por placeholders. No se incorporaron bases, respaldos, claves, tokens, contraseñas ni datos personales deliberados.
- El repositorio tiene `CLAUDE.md` y no tenía `AGENTS.md` en la raíz. El `AGENTS.md` recibido queda como referencia bajo `docs/handoff/`; no se sobrescribieron las instrucciones existentes.
- Los documentos fuente son reportes al 25/10 y no se consideran evidencia de estado actual salvo donde se confirmó contra esta rama.

## P1 — M4 Habilitaciones

| Criterio | Estado | Evidencia / límite |
|---|---|---|
| `permitFeeGenerated` y `commercialFineGenerated` bajo envelope Core de ocho campos | IMPLEMENTADO / PROBADO LOCALMENTE | `CoreM4Events` y `CoreM4Consumer`; se exige UUIDv4, versión `1.0`, `sourceModule=habilitaciones` y eventType correspondiente. M4 payload no fue modificado. |
| Campos y validación | IMPLEMENTADO / PROBADO LOCALMENTE | Validación anidada de `data.id`, solicitud/establecimiento/acta, CUIT de 11 dígitos, importe positivo, moneda y campos obligatorios. El origen de infracción es opcional en par y, si viene, identifica a M6. |
| Idempotencia por `eventId` y por identidad de origen | IMPLEMENTADO / PROBADO LOCALMENTE | La misma entrega devuelve el log existente; un `data.id` repetido para la misma fuente/eventType queda ignorado. |
| Evidencia y privacidad | IMPLEMENTADO / PROBADO LOCALMENTE | Se conserva el envelope original en `IntegrationEventLog` para reproceso. Los errores ya no repiten el CUIT; no se registra el payload en el logger de aplicación. |
| Organización por CUIT | PARCIAL / PENDIENTE DE PROYECCIÓN M1 | Si la proyección organizacional M1 tiene el mismo CUIT, la referencia se identifica. Si no existe, el evento queda `FAILED` con `M4_TAXPAYER_NOT_FOUND`; no se crea contribuyente ficticio. |
| Vencimiento | PENDIENTE — `M4_DUE_DATE_POLICY_PENDING` | El contrato M4 no trae fecha de vencimiento y no hay una política tributaria acordada/configurada en el modelo vigente. Los eventos válidos se guardan, no crean `ExternalObligation` ni `Debt`, y pueden reprocesarse con `POST /api/v1/integrations/events/{eventId}/reprocess` cuando la condición tenga una resolución implementada. No se inventa fecha. |
| Deuda única al disponer de todos los datos | PENDIENTE POR VENCIMIENTO | `ExternalObligation.dueDate` y `Debt.dueDate` son obligatorios; crear deuda sin fecha sería inválido. No se añadió migración ni SQL. |
| Pago parcial/total, pago dividido, cuota de plan y reversión de origen M4 | IMPLEMENTADO / PROBADO LOCALMENTE | Reutiliza el outbox económico compartido. `originType` permanece `PERMIT_FEE`/`COMMERCIAL_FINE`; `originId` conserva el ID original M4 (`data.id`), no `externalRef`; `infractionId` se omite. El pago en cuotas informa el capital efectivamente imputado a cada deuda, no el interés financiero de la cuota. |
| `correlationId` económico M4 | IMPLEMENTADO / PROBADO LOCALMENTE CON BLOQUEO SEGURO | Sólo se reutiliza el UUID de correlación presente en el evento M4 de origen y verificado contra el log. Si falta o no coincide, el evento económico queda `PENDING` para conciliación con `M4_ORIGINAL_CORRELATION_ID_UNAVAILABLE`; ya no se deriva causalidad desde un ID local. |
| Contrato saliente UUID de `paymentId`/`debtId` | PENDIENTE P2 | Esta rama mantiene el contrato actual compartido y sus pruebas. Los IDs transitorios actuales no se normalizaron aquí para no anticipar P2 ni crear una variante M4. |
| Aceptación de M4 / entrega por Core | PENDIENTE EXTERNO | El documento de M4 recibido es una versión de trabajo. **ACEPTADO POR DESTINO: no acreditado. ENTREGADO POR CORE: no acreditado.** No hubo prueba conjunta, ACK ni transporte real por Core; `LocalLog`/outbox no demuestra entrega. |

### Criterios P1 comprobables

| # | Criterio | Estado | Evidencia / bloqueo |
|---:|---|---|---|
| 1 | Tasa M4 válida | BLOCKED | Se valida y conserva en inbox, pero no se crea obligación/deuda mientras no exista regla de vencimiento. |
| 2 | Multa M4 válida | BLOCKED | Igual al criterio 1; no se fabrica una fecha ni un efecto económico. |
| 3 | Repetición del mismo `eventId` | PASS | `CoreM4ContractTests` y `PostgreSqlIntegrationTest`; se reutiliza el mismo log. |
| 4 | Mismo origen de negocio con otro `eventId` | PASS | `sameBusinessOriginWithAnotherEventIdIsIgnored`; segundo evento queda IGNORED. |
| 5 | Orden de llegada fee/fine | PASS | `coreM4FeeAndFineArrivalOrderDoesNotChangeTheirIndependentHandlingOnPostgreSql`; se prueban ambos órdenes sobre PostgreSQL. |
| 6 | CUIT sin correspondencia | PASS | PostgreSQL registra el evento fallido sin efectos económicos; reproceso al proyectar organización. |
| 7 | Moneda no soportada | PASS | Prueba PostgreSQL verifica `M4_UNSUPPORTED_CURRENCY`. |
| 8 | Campo requerido faltante | PASS | Prueba PostgreSQL verifica `INVALID_EVENT` para importe ausente. |
| 9 | Origen de infracción condicional | PASS | Se prueba par ausente permitido y fuente distinta de M6 rechazada. |
| 10 | Vencimiento no disponible | PASS | Se retiene como `M4_DUE_DATE_POLICY_PENDING`; no crea deuda incorrecta. |
| 11 | Reproceso del pendiente | PASS | Al resolver la proyección de CUIT, el error avanza al bloqueo de vencimiento; el payload original sigue registrado. |
| 12 | Una obligación/deuda por evento procesable | BLOCKED | Sin fecha no se procesa ninguna; unicidad no puede demostrarse hasta recibir política y habilitar la ruta económica. |
| 13 | Pago parcial | PASS | `mixedPaymentRoutesEachAllocationWithItsOwnAmount`; cantidad publicada coincide con la imputación. |
| 14 | Pago total | PASS | `totalM4PaymentUsesConcreteExternalObligationCorrelation`; `paymentRegistered` y `debtSettled`. |
| 15 | Reversión | PASS | `authorizedReversalPublishesAffectedExternalAllocation`; se emite tras aprobación/ejecución y preserva origen. |
| 16 | Idempotencia de publicación | PASS | `idempotentM4PaymentRetryDoesNotDuplicateEconomicEvents`; M4 mantiene un `paymentRegistered` y un `debtSettled`. |
| 17 | `originId` exacto | PASS | Tests de pago/reversión verifican `data.id` M4; no se sustituye por `externalRef`. |
| 18 | No regresión M7/M8 | PASS local | Suite completa backend, M7 PostgreSQL y tests contractuales M7/M8; no equivale a aceptación externa. |
| 19 | Privacidad en errores/logs | PASS local | Pruebas de privacidad y PostgreSQL; no se incluye CUIT en `errorMessage`. |
| 20 | Persistencia tras reinicio | N/A para esta etapa | Se prueba persistencia/reproceso en PostgreSQL efímero; no se reinició un proceso contra DB compartida. |

## Evidencia de esta etapa

- `backend`: `./mvnw clean verify` — BUILD SUCCESS; 411 tests, 0 failures, 0 errors, 0 skipped. Ejecutado con Temurin 21.0.12.1 (el compilador conserva `--release 17`).
- Testcontainers real: `postgres:17-alpine`, PostgreSQL 17.11; 36 tests en `BlockedBillPostgreSqlIntegrationTest` (2), `M5OwnedUuidMigrationPostgreSqlTest` (1), `M8ProductionPostgreSqlMigrationTest` (1) y `PostgreSqlIntegrationTest` (32). Migraciones comunes aplicadas en bases efímeras hasta V26.
- JaCoCo: líneas 92.28% (2,080/2,254), instrucciones 90.55% (30,887/34,111), ramas 66.08% (1,459/2,208); gate Maven PASS.
- `frontend`: `npm test` — 314 tests en 35 archivos PASS; `npm run build` PASS. Vite reportó solamente el aviso existente de chunk JS mayor a 500 kB.
- OpenAPI: la suite existente verificó `/v3/api-docs` y 148 paths; esta rama no modifica endpoints ni contratos HTTP.
- `git diff --check`: PASS.
- M4 se ejercitó en PostgreSQL con idempotencia, reproceso tras proyectar la organización, llegada fee/fine en ambos órdenes, validación de versión/CUIT/moneda/origen de infracción/campos requeridos, ausencia de efectos económicos antes del vencimiento definido y pago de cuota con principal aplicado, origen M4 y correlación originales.
- Estas pruebas no constituyen aceptación de M4 ni entrega por Core; el paquete M4 disponible sigue identificado como versión de trabajo.

## Próximo límite de decisión

Para eliminar `M4_DUE_DATE_POLICY_PENDING`, el PO/M4 debe definir la regla de vencimiento aplicable (referencia temporal, calendario y efecto de feriados si corresponde), y aclarar si la regla se configura por concepto o es común. Para la ruta de pago/reversión, M4/Core debe confirmar si la correlación es requerida y el comportamiento esperado cuando su evento de origen llega sin ella. La identidad por CUIT sólo funciona cuando existe la proyección autoritativa de la organización.
