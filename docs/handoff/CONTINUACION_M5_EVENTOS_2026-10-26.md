# Traspaso para continuar M5 Rentas — 26/10/2026

Este archivo es para quien continúe el trabajo desde GitHub. Leerlo junto con `docs/handoff/00_LEER_PRIMERO.md`, `AGENTS.md` y los contratos originales; los documentos del 25/10 son un corte histórico y no reemplazan el código de esta rama.

## Punto de partida

- Repositorio: `DAI2-Modulo-de-rentas-deudas-y-tributos/Modulo-5`.
- Rama de esta entrega: `feature/m5-economic-uuid-proposal-20261026`, creada sobre `feature/m5-handoff-p1-m4-completo` en `ca01aed`. Es una **feature apilada**: hasta integrar la rama base, comparar o abrir el PR contra ella, no contra `develop`, para no atribuirle sus commits a esta entrega.
- Antes de editar: clonar/actualizar, comprobar `git status --short --branch`, `git log -1 --oneline` y comparar este informe con el código efectivamente descargado.
- Los cambios de esta entrega son backend, migración y pruebas, más contratos económicos **propuestos**. No hay cambios de frontend.

## Qué quedó implementado localmente

1. M4: se preservan `originType` y `originId` del evento fuente; la conciliación de movimientos económicos diferidos ahora admite M4 además de M7 y valida tipo de evento, origen, instantánea e IDs. CUIT fue confirmado como dato entrante por el responsable. El vencimiento de tasas/multas **no** fue acordado por M4: no inventar `dueDate` ni dar por cerrada la creación de obligaciones. Con correlación entrante `null`, el movimiento económico local se conserva y su evento queda diferido; no se fabrica un UUID.
2. UUID de propiedad M5: `payment.integration_id` persistente (Flyway V27); `debt.integration_id` existente desde V25. `paymentRegistered`, `paymentReversed` y `debtSettled` externos usan esos UUID en lugar de `payment-<Long>`/`debt-<Long>`. Las PK `Long` no cambian; `originId` sigue siendo el ID original M4/M7. `citizenId` se omite si no hay UUID canónico M1 verificado, y nunca se deriva de DNI, CUIT o PK.
3. Protección de históricos: outbox con IDs económicos legados pasa a revisión; snapshots diferidos legados no se convierten silenciosamente. `debtSettled` de `MUNICIPAL_TAX` sigue como candidato no encolado ni publicado.
4. Se agregaron pruebas de migración, contrato, conciliación y seguridad del outbox. Se corrigieron dos pruebas: una comparación UUID/string y un fixture temporal sensible al minuto; no se cambió la regla de negocio de recargos.

Archivos clave: `backend/src/main/java/ar/gob/municipalidad/rentas/integration/producer/ConfirmedIntegrationOutbox.java`, `DeferredEconomicReconciliation.java`, `backend/src/main/java/ar/gob/municipalidad/rentas/integration/event/outbound/ConfirmedOutboundEvents.java`, `backend/src/main/java/ar/gob/municipalidad/rentas/IntegrationServices.java`, `backend/src/main/resources/db/migration-postgresql/V27__payment_owned_integration_id.sql` y sus pruebas bajo `backend/src/test/`.

## Contrato propuesto, no aceptación externa

- Especificación única: `contracts/events/M5_ECONOMIC_OUTBOUND_UUID_DRAFT.md`.
- Adaptación para M4/M7/M8: `contracts/events/M5_ECONOMIC_ADAPTATION_M4_M7_M8_PROPOSAL.md`.
- JSON Schema y ejemplos: `contracts/schemas/m5-economic-outbound.proposed.schema.json` y `contracts/events/examples/m5-economic/`.
- Validador: `contracts/tests/Validate-M5EconomicProposal.ps1` (requiere PowerShell 7 para `Test-Json -SchemaFile`).

Se conserva el envelope de Core de ocho campos. Los tres eventos compartidos usan un solo payload por tipo, no variantes por destinatario. El valor local actual `eventVersion="1.0"` **no** está aprobado para la transición a UUID: Core y consumidores deben acordar si se conserva o se versiona. `paymentRegistered`, `debtSettled` y `paymentReversed` pueden recibirse duplicados o fuera de orden; cada receptor debe deduplicar `eventId`, manejar dependencias pendientes y conciliar saldos sin usar el orden de llegada como verdad.

## Evidencia de pruebas y límites

- El responsable ejecutó `M5OwnedUuidMigrationPostgreSqlTest` contra PostgreSQL aislado: **1 prueba, 0 fallas, BUILD SUCCESS**.
- El responsable ejecutó después la suite completa backend: **417 pruebas, 0 fallas, 0 errores, 0 omitidas, BUILD SUCCESS**, el 26/10/2026 a las 10:49:46 -03. Dos ejecuciones previas detectaron las fallas de prueba mencionadas arriba; la última es la evidencia verde.
- Validación documental local: **8 ejemplos JSON en archivo y 4 inline válidos; 14 mutaciones inválidas rechazadas**; coherencia de pago distribuido/reversión verificada. `git diff --check` pasó antes de preparar esta entrega.
- No se ejecutó V27 sobre la base local `rentas`; no se activó RabbitMQ/Core, no se enviaron eventos reales ni se hicieron pruebas E2E entre módulos. `PUBLISHED` del publicador local no demuestra recepción por Core.
- El amigo debe repetir las pruebas en su entorno y usar una base PostgreSQL **aislada** para V27; no aplicar la migración sobre `rentas` sin autorización, respaldo y revisión de datos. Desde `backend/`, en Windows: `.\mvnw.cmd test`; para validar la propuesta, desde la raíz con PowerShell 7: `pwsh -File contracts/tests/Validate-M5EconomicProposal.ps1`.

## Decisiones pendientes antes de activar la integración

| Responsable | Confirmar |
|---|---|
| M4 | Regla de vencimiento de tasas/multas entrantes; aceptación de UUID económicos, `citizenId` omitido/opcional, catálogo `conceptCode` y respuesta con `correlationId:null` cuando el origen fue nulo. CUIT entrante está confirmado por el responsable de M5, pero verificar versión final del contrato M4. |
| M7 | Recepción de UUID textuales, omisión de `citizenId`, semántica `TRAFFIC_INFRACTION` frente a `TRAFFIC_FINE` y conciliación de hechos fuera de orden. |
| M8 y M1 | UUID canónico de ciudadano, personas jurídicas, filtrado por `originType` y aceptación de la variante `MUNICIPAL_TAX`; hasta entonces no publicarla. |
| Core y M4/M7/M8 | `eventVersion`, transición desde IDs antiguos, política de correlación/causalidad y ruteo/ack. No cambiar el envelope unilateralmente. |
| M5 | Auditar outbox/snapshots históricos con IDs legados, validar migración V27 en entorno aislado del colaborador y preparar pruebas conjuntas luego de los acuerdos. |

## Siguiente paso seguro para el colaborador

Revisar el diff de esta feature sobre `feature/m5-handoff-p1-m4-completo`, repetir pruebas, inspeccionar V27 y comparar el contrato propuesto con las implementaciones consumidoras de M4/M7/M8. Registrar por escrito respuestas/versiones de los otros equipos antes de distribuir como contrato definitivo o conectar Core. No modificar contratos entrantes ajenos ni inventar identidad M1/correlación/vencimiento.
