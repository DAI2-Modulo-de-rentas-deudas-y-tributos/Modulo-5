# P4 — Auditoría segura de movimientos económicos históricos

**Alcance:** diagnóstico de sólo lectura y regresión con datos sintéticos en PostgreSQL 17.11/Testcontainers. No es un inventario de la base real `rentas`.

## Resultado y límites

| Categoría | Evidencia disponible | Clasificación | ¿Conciliación automática segura? | Revisión manual / dependencia |
|---|---|---|---|---|
| Snapshot y origen verificables, conciliación todavía pendiente | Snapshot persistido, IDs UUID y evento de origen con correlación | `DEFERRED` | No por el diagnóstico; el servicio normal aún debe verificar dominio y origen | M7/M4/Core para causalidad y entrega |
| Snapshot ausente o inválido | No existe el hecho económico original suficiente | `HISTORICAL_SNAPSHOT_UNAVAILABLE` | No | Sí; documentos originales y aprobación explícita |
| IDs `payment-<Long>` / `debt-<Long>` | Representación previa no equivalente al UUID persistente | `LEGACY_IDS_REVIEW_REQUIRED` | No; nunca se convierten automáticamente | Sí; correlación documental y política de transición |
| Cuota `CANCELLED` con imputación `ACTIVE` | La cuota no admite reversión aunque la imputación siga activa | `UNSAFE_REVERSAL_BLOCKED` | No | Sí; no reactivar ni revertir por inferencia |
| Plan multideuda sin distribución histórica exacta | Falta ledger de capital por deuda | `MANUAL_REVIEW_REQUIRED` | No; prorratear sería inventar importes | Sí; evidencia contable por obligación |
| Outbox local ya preparado | Existe `OutboxEvent` local, sin confirmación externa | `LOCAL_OUTBOX_PREPARED_CORE_UNCONFIRMED` | No se vuelve a generar otro evento; el estado local no prueba entrega | Core debe confirmar transporte/recepción |
| Snapshot/origen y ledger exactos | Evidencia estructural consistente | `VERIFIED` sólo para esa evidencia | No significa autorización para aplicar cambios | Resolver según operación y contrato externo |

Las clasificaciones son valores de diagnóstico de tests, no estados persistentes de dominio. Un `DEFERRED` no significa “reparable”; sólo que hay evidencia suficiente para que el servicio de conciliación existente realice sus propias comprobaciones. `PUBLISHED` local no demuestra recepción por Core.

## Protecciones implementadas y probadas

- `DeferredEconomicReconciliation` envía a revisión marcadores sin snapshot, IDs económicos legados o evidencia de origen inválida; correlación todavía ausente conserva el movimiento como diferido.
- La clave de idempotencia y el `eventId` guardados en el snapshot/outbox permiten verificar la repetición sin crear otra salida.
- La reversión comprueba la cuota y la distribución antes de alterar pagos, asignaciones, deudas, cuotas, plan o outbox. `INSTALLMENT_CANCELLED` y `HISTORICAL_PLAN_DISTRIBUTION_UNVERIFIABLE` quedan bloqueados con auditoría del bloqueo.
- `HistoricalMovementDiagnostics` vive únicamente en `src/test`: consulta logs, outbox, asignaciones, cuotas, planes y ledger; no llama al reconciliador ni a servicios mutantes y no guarda su clasificación.
- `HistoricalMovementAuditPostgreSqlTest` crea los casos sintéticos en un contenedor PostgreSQL 17.11. El test modifica filas sólo para preparar casos incompletos y verifica que la operación de reversión no cambie el estado económico. Ningún dato real se importa.
- Las regresiones existentes cubren pagos parciales/totales, snapshots diferidos, correlación incorrecta o faltante, IDs legados, repetición, reversión válida, cuota cancelada, ledger multideuda ausente y reinicio físico. Verificar los resultados actuales en la ejecución final; los resultados históricos no se presentan como ejecución de esta revisión.

### Ejecución de esta revisión (26/10/2026)

- `HistoricalMovementAuditPostgreSqlTest`: 2/2; PostgreSQL 17.11 real efímero; 0 fallas, 0 errores, 0 omitidos.
- Regresiones focalizadas (diagnóstico, reinicio físico, eventos externos, PostgreSQL, V27/V28, perfil M8 y aislamiento de esquema): 72 pruebas, 0 fallas/errores/omitidos.
- `./mvnw clean verify`: BUILD SUCCESS; 426 pruebas, 0 fallas, 0 errores, 0 omitidos. Flyway aplicó V28 en los contenedores de integración.
- JaCoCo: líneas 91,52%; instrucciones 90,22%; ramas 65,32%; gate configurado aprobado.
- Reinicio físico: dos procesos independientes sobre PostgreSQL 17.11 efímero; conciliación repetida devolvió `ALREADY_PREPARED` y mantuvo un evento en outbox. Entrega/ACK de Core no confirmado.
- Ejecución con Eclipse Temurin 21.0.12, `javac --release 17`; `JAVA_HOME` no estaba configurado globalmente y se fijó sólo en el proceso PowerShell de Maven.
- `git diff --check`: sin errores. No se conectó a `rentas`; no se crearon migraciones ni se hicieron operaciones Git mutantes.

## Estado de la imputación histórica N.º 3

**No inspeccionada en datos reales.** La documentación del PO reporta cuota `CANCELLED` e imputación `ACTIVE`, pero esta sesión no accedió a la base `rentas` ni a un respaldo autorizado. No se afirma que el registro exista en el contenedor sintético ni se alteró. Con el comportamiento actual, una combinación así debe clasificarse `UNSAFE_REVERSAL_BLOCKED` y permanecer intacta.

## Procedimiento futuro para inventario real autorizado

1. Obtener autorización escrita del responsable de datos/PO y del DBA, alcance de tablas/fechas/IDs y ventana de consulta. Confirmar que se trata de un acceso de sólo lectura; no reutilizar credenciales de producción en desarrollo.
2. Crear/verificar respaldo recuperable y plan de restauración antes de cualquier paso posterior que pudiera escribir. Para el inventario, usar preferentemente una réplica consistente o snapshot restaurado aislado con acceso de sólo lectura.
3. Revisar primero esquema, índices y volúmenes con consultas agregadas sanitizadas. Enumerar sólo identificadores internos necesarios, tipos/estados/fechas/importes; no copiar DNI/CUIT, payloads completos, tokens ni secretos al informe, logs o Git.
4. Clasificar filas una por una contra el snapshot original, ledger, evento fuente, `eventId`, clave idempotente, outbox y recibo/ACK verificable. Guardar evidencia con acceso restringido y hashes; no inferir montos ni completar relaciones por proximidad temporal.
5. Dos personas revisan cada propuesta de conciliación/reversión. Separar el informe de lectura de una eventual operación de escritura; cualquier reparación necesita ticket, aprobación explícita, respaldo, plan de reversa, simulación en copia y aprobación operativa aparte.
6. Para eventos externos, confirmar con M7/Core el `eventId`, causalidad, aceptación del evento y política de duplicados. Outbox `PUBLISHED` por sí solo no sirve como ACK de destino.

### Evidencia mínima antes de autorizar recuperación

- snapshot económico original inmutable y consistente con pago, imputación, cuota, plan y deuda;
- ID persistente M5 y referencia de origen M7/M4 sin conversión inventada;
- distribución exacta de principal por deuda, si el plan cubre más de una;
- correlación y `eventId` estables; existencia/estado de outbox y confirmación externa separadas;
- conciliación de saldos antes/después calculada desde registros contables fuente, no estimada;
- aprobación escrita del responsable funcional y de operación/DBA, más rollback ensayado.

## Dependencias pendientes

- Imputación real N.º 3 y cualquier otro histórico: requieren acceso autorizado a una réplica/restauración, no están verificadas aquí.
- M7 debe confirmar el catálogo `infractionType`, la semántica `TRAFFIC_INFRACTION`/`TRAFFIC_FINE` y formato/identidad esperada de `citizenId`.
- Core debe confirmar transporte, ACK de recepción, reintentos, DLQ y versión del contrato. Core/RabbitMQ no se activaron.
- La prueba física de reinicio valida persistencia local en PostgreSQL aislado, no entrega entre módulos.

## Dictamen P4

- **Parte local verificable:** protegida por reglas existentes y con regresión sintética PostgreSQL para clasificación y ausencia de efectos económicos ante reversión insegura, sujeto al resultado de la suite ejecutada en esta revisión.
- **Pendiente:** inspección del inventario histórico real con autorización y respaldo; acuerdos externos M7/Core; no existe evidencia para dar por recuperable la imputación N.º 3.
- **No autorizado/realizado en este trabajo:** escritura en `rentas`, reparación de históricos, migraciones nuevas o cambios de negocio.
