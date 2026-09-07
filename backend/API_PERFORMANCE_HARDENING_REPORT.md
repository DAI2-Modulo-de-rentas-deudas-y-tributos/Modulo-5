# API Performance Hardening — Módulo 5 Rentas

Fecha de validación: 2026-08-31
Rama inspeccionada: `feature/backend-rentas-hardening`

## Resultado ejecutivo

La etapa cerró los once hallazgos internos que requerían una optimización de consulta. Los otros seis GET originalmente marcados para revisión conservan su respuesta actual porque cambiarla rompería el contrato; cuatro cuentan con un listado paginado equivalente, auditoría cuenta con su consulta paginada general y las cuotas están acotadas por la configuración del plan. No quedan GET en revisión ni bloqueados por dependencias externas.

Los dos POST observados no admiten una solución interna segura: la clave de idempotencia HTTP de pagos requiere un contrato con los consumidores y las notificaciones salientes a M7 requieren su contrato de eventos y el broker. Ambos se clasifican como `EXTERNAL_BLOCKED`.

No se modificaron frontend, infraestructura, Terraform/AWS, Core/JWT, broker ni el contrato M7 inbound. No se creó commit, push, merge ni despliegue.

## Inventario de los hallazgos

| Método | Ruta | Problema original | Severidad | Interno/externo | Acción | Estado final |
| --- | --- | --- | --- | --- | --- | --- |
| GET | `/taxpayers/{id}/summary` | Recorría todos los planes para contar los activos. | Media | Interno | `COUNT` filtrado por contribuyente y estado en base. | OK — corregido |
| GET | `/liquidations` | Una consulta de componentes por liquidación. | Alta | Interno | Carga por lote para los IDs de la página y agrupación en memoria. | OK — corregido |
| GET | `/debts` | Consultas de pertenencia a plan por cada deuda. | Alta | Interno | Dos consultas por lote, una para planes simples y otra para planes multideuda. | OK — corregido |
| GET | `/taxpayers/{id}/debts` | Mismo N+1 de pertenencia a plan. | Alta | Interno | Reutiliza el armado por lote respetando el filtro del contribuyente. | OK — corregido |
| GET | `/bills` | Una consulta de `BillDebt` por boleta. | Alta | Interno | Carga por lote de las relaciones de las boletas de la página. | OK — corregido |
| GET | `/taxpayers/{id}/bills` | Mismo N+1 de deudas por boleta. | Alta | Interno | Reutiliza el armado por lote y conserva ownership. | OK — corregido |
| GET | `/payments/{id}/allocations` | Colección hija sin página. | Baja | Interno, con restricción de compatibilidad | Se conserva el contrato; existe `/payment-allocations?paymentId=...` paginado. | OK — compatibilidad preservada |
| GET | `/taxpayers/{id}/credit-balances` | Colección hija sin página. | Baja | Interno, con restricción de compatibilidad | Se conserva; existe `/credit-balances?taxpayerId=...` paginado. | OK — compatibilidad preservada |
| GET | `/payment-plans/{id}/installments` | Colección hija sin página. | Baja | Interno, acotado por dominio | Se conserva: la solicitud admite como máximo 60 cuotas, más un posible anticipo. | OK — acotado |
| GET | `/payment-plans/defaulted` | Cargaba planes y cuotas para filtrar en memoria. | Alta | Interno | Consulta paginada con subconsulta correlacionada de cuotas vencidas. | OK — corregido |
| GET | `/taxpayers/{id}/exemptions` | Colección hija sin página. | Baja | Interno, con restricción de compatibilidad | Se conserva; existe `/exemptions?taxpayerId=...` paginado. | OK — compatibilidad preservada |
| GET | `/taxpayers/{id}/benefits` | Colección hija sin página. | Baja | Interno, con restricción de compatibilidad | Se conserva; existe `/social-benefits?taxpayerId=...` paginado. | OK — compatibilidad preservada |
| GET | `/indicators/summary` | Escaneos completos de pagos y deudas. | Alta | Interno | Reutiliza tres agregaciones de base de datos. | OK — corregido |
| GET | `/indicators/collection` | Cargaba y filtraba pagos en Java. | Alta | Interno | `COUNT` y `SUM` condicionados en base con rango de fechas. | OK — corregido |
| GET | `/indicators/debt` | Cargaba todas las deudas para calcular totales. | Alta | Interno | Proyección agregada con conteos y sumas. | OK — corregido |
| GET | `/indicators/delinquency` | Cargaba todas las deudas para calcular mora. | Alta | Interno | Agregación condicional según estado, saldo y vencimiento. | OK — corregido |
| GET | `/audit/entities/{type}/{id}` | Historial hijo sin página. | Baja | Interno, con restricción de compatibilidad | Se conserva; `/audit?entityType=...&entityId=...` es la alternativa paginada. | OK — compatibilidad preservada |
| POST | `/payments` | No hay clave de idempotencia HTTP; eventual salida M7 pendiente. | Alta | Externo | Definir encabezado, alcance, retención y replay con consumidores; definir evento M7. | EXTERNAL_BLOCKED |
| POST | `/payment-reversals/{id}/execute` | Eventual notificación saliente a M7 sin contrato. | Media | Externo | Esperar contrato de evento, broker, ACK, retry y DLQ. | EXTERNAL_BLOCKED |

## N+1

### Liquidaciones

Antes, el listado ejecutaba una consulta para la página y una consulta adicional de componentes por cada liquidación. Ahora `LiquidationService.responses(Page<Liquidation>)` consulta los componentes de todos los IDs de la página en un solo lote y los agrupa por `liquidationId`.

La prueba con cinco liquidaciones y dos componentes cada una registra una consulta durante el armado de respuestas, independientemente de la cantidad de filas de la página. La consulta inicial de la página se ejecuta antes de la medición.

### Deudas

Antes, cada deuda consultaba por separado si pertenecía a un plan simple o multideuda. Ahora el servicio obtiene los IDs asociados mediante dos consultas por lote y arma los DTO sin cargar los planes completos.

La prueba con cinco deudas registra dos consultas durante el armado de respuestas: una por cada forma válida de vinculación con un plan. El número es estable respecto del tamaño de la página.

### Boletas

Antes, cada boleta consultaba su colección `BillDebt`. Ahora todas las relaciones de las boletas de la página se obtienen con un único `IN` y se agrupan por `billId`.

La prueba con cuatro boletas y dos deudas por boleta registra una consulta durante el armado y también verifica los IDs y el total de cada respuesta.

No se activó `EAGER` ni Open Session in View. `spring.jpa.open-in-view=false` se mantiene y las consultas necesarias se realizan dentro de servicios `readOnly`.

## Indicadores

- Antes: `findAll()` de pagos y deudas, seguido de filtros, conteos y sumas en Java.
- Después: tres proyecciones agregadas ejecutadas por la base, sin materializar las entidades.
- Consultas agregadas:
  - `PaymentRepository.aggregateConfirmed(from, to)`: pagos confirmados, total, imputado y no imputado por rango.
  - `DebtRepository.aggregateDebt()`: cantidad total, pagadas, abiertas, importe original y saldo.
  - `DebtRepository.aggregateDelinquency(today)`: deudas abiertas y vencidas e importe vencido.

Las expresiones usan `COALESCE` para conservar la respuesta numérica en cero cuando no existen filas. Los tests comparan importes y conteos, incluyendo filtros de fecha y base vacía. `/indicators/summary` completa sus tres secciones con tres consultas.

## Planes de pago

- El resumen del contribuyente usa `countByTaxpayerIdAndStatus` en vez de cargar todos los planes.
- El listado de planes caídos se filtra y pagina en la base con una subconsulta de cuotas impagas vencidas comparada contra `maxOverdueInstallments` de la configuración del plan.
- La prueba funcional conserva la regla de caducidad y confirma que el plan aparece en la consulta paginada.
- La prueba de conteo registra entre una y dos sentencias, según si Spring Data necesita ejecutar la consulta de conteo de página.

## Paginación y compatibilidad

- Endpoints modificados: ninguno cambió su contrato HTTP ni el tipo de respuesta.
- Endpoints deliberadamente no modificados: allocations de un pago, saldos del contribuyente, cuotas del plan, exenciones del contribuyente, beneficios del contribuyente e historial de una entidad.
- Compatibilidad: se conservaron las listas existentes. Para las colecciones potencialmente abiertas existen listados generales paginados con filtros equivalentes; las cuotas están acotadas por dominio.
- Convención: continúan `page`, `size` y `sort` en los endpoints paginados existentes.
- No se agregó un máximo global de página porque el proyecto no tenía un contrato previo de `size <= 100`; imponerlo en esta etapa sería un cambio de API no solicitado.

## Base de datos

- Nueva migración: `V12__performance_query_indexes.sql`.
- Nuevos índices:
  - `idx_payment_status_paid_at` sobre `payment(status, paid_at)`, para la agregación de recaudación por estado y período.
  - `idx_payment_plan_status` sobre `payment_plan(status)`, para localizar planes activos antes de evaluar sus cuotas vencidas.
- Motivo: responden a las dos consultas nuevas que comienzan por esos predicados.
- No se duplicaron índices ya presentes para componentes de liquidación, relaciones de boleta, deuda, cuotas, auditoría ni integración.
- Las migraciones anteriores no fueron editadas.

## Auditoría final GET/POST

### GET

- Total: 74
- OK: 74
- Corregidos internamente: 11
- Revisión restante: 0
- EXTERNAL_BLOCKED: 0
- Compatibilidad preservada o colección acotada: 6

### POST

- Total: 59
- OK: 57
- Corregidos internamente: 0
- Revisión restante: 0
- EXTERNAL_BLOCKED: 2

Los 3 PATCH continúan OK. No existen PUT ni DELETE. El conteo y los contratos OpenAPI no cambiaron.

## Tests y evidencia

- Comando: `.\mvnw.cmd clean verify`
- Resultado: `BUILD SUCCESS`
- Detectados: 85
- Aprobados: 80
- Fallos: 0
- Errores: 0
- Omitidos: 5 Testcontainers
- Cobertura de líneas: 789/889, 88,75%
- Gate JaCoCo: >=85%, cumplido
- OpenAPI: 122 paths / 136 operations
- Flyway en H2: 12 migraciones aplicadas
- PostgreSQL real: `POSTGRESQL_REAL_PENDING`

Los cinco tests PostgreSQL permanecen preparados y no fueron sustituidos por H2. La máquina no dispone de un entorno Docker utilizable; por lo tanto, este informe no afirma una validación PostgreSQL real.

## Bloqueos externos

- M4: contrato real pendiente; no se inventó integración.
- M7 outbound: pendientes nombre y versión de eventos, payload, destino, ACK, retry y DLQ.
- Core/JWT: pendiente proveedor de identidad y contrato de claims/roles; no se agregó seguridad ficticia.
- Broker: pendientes tecnología, conexión, tópicos/colas, schemas y credenciales.

## Variables de entorno / secretos

Sin cambios en variables de entorno ni secretos.

- Agregadas: ninguna.
- Modificadas: ninguna.
- Eliminadas: ninguna.
- Secretos nuevos: ninguno.
- Requiere aviso al equipo: No.
- Requiere actualizar CI/CD: No por variables; CI sí debe ejecutar los tests PostgreSQL ya existentes con Docker disponible.
- Requiere actualizar `.env.example`: No.

## Archivos de esta etapa

- Código: `Repositories.java`, `DomainServices.java`, `ApiResponses.java`, `QueryServices.java`, `PlanWorkflowService.java`, `ApiController.java`.
- Base de datos: `V12__performance_query_indexes.sql`.
- Tests: `ApiPerformanceTests.java`, `IndicatorTests.java`, `PaymentPlanFlowTests.java`, `MigrationValidationTest.java`, `PostgreSqlIntegrationTest.java`.
- Documentación: `README.md`, este informe y una guía de aprendizaje local fuera del repositorio.

El árbol de trabajo ya contenía cambios de etapas anteriores y todos fueron preservados. Este reporte enumera únicamente el alcance de performance y API hardening de esta etapa.
