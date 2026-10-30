# Estado local consolidado de M5 al 30/10/2026

Este documento distingue contratos aceptados e implementación local de la
interoperabilidad E2E. Las pruebas de M5 no acreditan que Core haya recibido
un evento ni que M1, M2, M4, M7 o M8 lo hayan procesado.

## Integraciones

| Módulo | Estado local | Pendiente externo |
|---|---|---|
| M1 | El listener recibe `citizenUpdated`, `organizationRegistered`, `representationGranted` y `representationExpired` desde el envelope Core; conserva `eventId`, correlación y causalidad. Los salientes de exenciones y planes requieren UUID canónico verificado. | Despliegue y vínculo confiable del UUID de ciudadano, claim final de login/JWT, registro de publicaciones y prueba E2E. |
| M2 | `ticketUpdated` y los seis payloads `updateTicketStatus` v1.78 aprobados tienen pruebas locales. | Ticket real publicado por M2 y evidencia de recepción/consumo por Core/M2. |
| M4 | Contrato integral recibido; entrada y hechos económicos compartidos validados localmente. | UUID estables de origen, plazos de vencimiento acordados, variante de `debtSettled` sin `citizenId` para organizaciones y E2E. |
| M7 | `infractionConfirmed` y pagos, cancelaciones y reversiones directos o por cuotas validados localmente. | Evento real de M7, correlación recuperable para casos históricos y confirmación E2E. |
| M8 | `socialBenefitUpdated`, reglas, exenciones y `debtOverdue` V3 probados localmente. `overdueDebt` es el nombre histórico V2. `debtSettled` municipal se prepara con UUID canónico. | Entrega, ruteo y consumo reales; vínculo canónico M1. |

El outbox distingue preparación local de entrega. `LOCAL_ONLY` y el estado
histórico `PUBLISHED` no confirman entrega; `BROKER_CONFIRMED` sólo acredita
recepción por un broker. La aceptación HTTP 202 de Core tampoco acredita
consumo del módulo destinatario. El transporte, registro de
publicaciones/suscripciones, ACK, retry y DLQ requieren prueba E2E. La
validación actual de `eventId` entrante como UUIDv4 debe cotejarse con el
esquema vigente de Core; los ejemplos ilustrativos no bastan para cambiarla.

## Migraciones pendientes de autorización

La última comprobación exitosa comunicada de `rentas` mostró Flyway V28, con
V25 y V27 aplicadas. Este documento **no confirma el estado actual** de la
base. No se ejecutaron V30 ni V31 durante esta revisión.

| Versión | Ruta | Efecto previsto |
|---|---|---|
| V29 | `db/migration-demo` | Tabla `demo_login_attempt` con clave hash, ventana, contador limitado e índice temporal. Se carga en el perfil local; no se carga en `prod`. |
| V30 | `db/migration` | Agrega `submitted_by` a `tax_configuration`, `liquidation_run`, `social_benefit_rule`; `exception_submitted_by` a `payment_plan_request` y `refinancing_request`. Todas son columnas `VARCHAR(255)` anulables. |
| V31 | `db/migration` | Agrega `lock_version BIGINT NOT NULL DEFAULT 0` a `tax_concept` y `tax_configuration`. Crea `idempotency_request` con clave primaria compuesta (`actor_id`, `operation`, `idempotency_key`), huella, respuesta opcional y fecha de creación. |

Flyway ordena por número entre las rutas configuradas, no por directorio. En
el perfil local el orden esperado desde V28 es V29, V30, V31. En `prod`, que
excluye `db/migration-demo`, V29 no está disponible: V30 y V31 son las
pendientes previstas. V30 depende de `social_benefit_rule` creada en V26;
V31 depende de tablas existentes desde versiones anteriores. Los scripts no
contienen `DROP`, `DELETE`, `UPDATE` ni cambios de PK. V31 asigna `0` a la
nueva columna de bloqueo de filas existentes; eso requiere verificar el
volumen y la ventana de bloqueo antes de ejecutar en una base real. Si las
columnas o la tabla ya existieran fuera de Flyway, el despliegue fallaría y
habría que investigar la diferencia, no reparar el historial a ciegas.

Plan para una autorización posterior: detener el backend que apunta a
`rentas`; consultar en modo sólo lectura nombre, versión PostgreSQL, historial
Flyway, tablas y columnas y cantidades; comparar con la última evidencia;
verificar que V29/V30/V31 no estén aplicadas ni haya objetos incompatibles;
generar un respaldo nuevo fuera del repositorio y verificar su integridad;
restaurarlo en una base aislada sin sobrescribir otra; aplicar allí las
migraciones con el mismo perfil/rutas del despliegue previsto; verificar
historial, datos y pruebas. Sólo después, con autorización separada, aplicar
las pendientes a `rentas` y repetir comprobaciones. No iniciar el backend
contra `rentas` antes de completar ese control, porque Flyway está habilitado.

Los documentos anteriores con nombres, payloads o estados antiguos se
conservan como antecedentes fechados. En particular,
`contracts/events/M5_ECONOMIC_OUTBOUND_UUID_DRAFT.md` documenta una propuesta
histórica y no el estado actual de aceptación.
