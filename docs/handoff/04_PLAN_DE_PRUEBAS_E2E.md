> **Aclaración de secuencia:** antes de activar Core, los eventos **salientes M5** se actualizan a UUID propios, se prueban y se entrega su contrato a cada consumidor. En las pruebas E2E hay que verificar **los mensajes entrantes tal como son emitidos por M1/M2/M4/M7/M8** y **el envelope Core inalterado**.

# Plan de calidad, pruebas E2E y sincronización visible entre portales

**Propósito:** demostrar que el sistema municipal funciona como ecosistema, no sólo que M5 genera JSON válidos. Ejecutar cuando Core y los servicios de M1/M2/M4/M7/M8 estén desplegados en un **entorno de pruebas acordado**. Hasta entonces, mantener fixtures y pruebas contractuales locales.

## 1. Cinco niveles de prueba (no son equivalentes)

1. **Unidad/dominio:** cálculo, estado, autorizaciones y no duplicación (mvn test; Jest/Vitest del frontend conforme scripts reales).
2. **Integración con PostgreSQL:** migrations V1–V26, escritura transaccional de ledger/outbox, concurrencia, consultas/read models, estados persistentes.
3. **Contrato/fixtures:** nombre/version, envelope, payload requerido, enums, UUID o strings según versión ratificada, null/ausencias, importes/fechas; cotejar por consumidor.
4. **Integración real de mensajería:** entrega a Core/broker, ack del destino, redelivery, DLQ, claves idempotencia, correlación y sin orden garantizado.
5. **E2E entre portales:** acción en portal A → operación backend → evento → estado backend B → información visible en portal B después de sincronización/refresh, sin mentir sobre «enviado»/«pagado».

## 2. Matriz E2E mínima

| ID | Acciones de usuario | Evento / efectos esperados | Evidencia obligatoria |
|---|---|---|---|
| E2E-01 | Crear ticket identificado en M2 y derivarlo a M5 | `ticketUpdated/ROUTED`: el mismo `ticketId` aparece en la bandeja de Rentas, categoría/subcategoría correctas | Logs de M2/Core/M5, BD y captura de ambos portales |
| E2E-02 | Empleado M5 inicia y resuelve ticket | `updateTicketStatus/STARTED` y `RESOLVED`: M2 refleja estado canónico, mensaje público visible; `updatedBy.id` UUID real M5 | Evento y respuesta M2; vistas tras reload |
| E2E-03 | M2 solicita reclasificación `RECLASSIFICATION` y redirige | M5 ignora eventos administrativos; ROUTED con `reassignment=true` actualiza ticket existente sin duplicarlo | Una sola fila/ticket en M5, estados correctos en ambos portales |
| E2E-04 | M4 genera tasa de habilitación | `permitFeeGenerated`: Rentas crea una obligación única, CUIT y monto correctos | `data.id` M4 preservado y obligación en Rentas |
| E2E-05 | M4 genera multa comercial | `commercialFineGenerated`: M5 crea deuda de origen `COMMERCIAL_FINE`; referencia administrativa no sustituye originId | Registro en portales M4/M5, referencia idéntica |
| E2E-06 | Rentas recibe pago parcial de tasa/multa M4 | `paymentRegistered`: M4 refleja pago parcial y saldo restante correcto, no lo marca cancelado | Imputación por deuda, mensaje/ack y UI M4 |
| E2E-07 | Rentas cancela por completo tasa/multa M4 | `paymentRegistered` + `debtSettled`; M4 marca obligación saldada | Saldos 0 en M5/M4, no duplicación |
| E2E-08 | Revertir pago ejecutado sobre obligación M4 | `paymentReversed`: saldo de M4 se recompone, sin perder traza | Ledger M5, evento recibido M4, ambas pantallas |
| E2E-09 | M7 confirma infracción | `infractionConfirmed`: se crea **una** deuda M5 por `infractionId` UUID original y finalAmount | Infracción en M7, deuda de tránsito en M5 |
| E2E-10 | Pagar multa M7 parcialmente | `paymentRegistered`, M7 conserva originId=infractionId y ve saldo correcto | Core ack, cambios en portal M7 |
| E2E-11 | Cancelar total multa M7 | `debtSettled`, M7 indica cancelación y 0 pendiente | Comparación de IDs/saldos/estados en ambos portales |
| E2E-12 | Pagar multa M7 mediante cuota de plan | Misma imputación por deuda que pago directo; si salda, `debtSettled` | Capital distribuido, snapshots, UI tras reload |
| E2E-13 | Revertir cuota de plan permitida | `paymentReversed` por deuda con importe original exacto; M7 actualiza saldo | Ledger/auditoría/outbox/UI; sin recalcular proporciones |
| E2E-14 | Intentar revertir cuota CANCELLED | Error de negocio, cero variación de saldo/cuota/eventos | Captura de error y hashes de datos estables |
| E2E-15 | M8 aprueba beneficio de exención social | `socialBenefitUpdated/APPROVED`: Rentas vincula ciudadano **canónico** y regla autorizada por programa, crea descuento sobre liquidaciones nuevas elegibles | IDs M1/M8 iguales, regla auditada, cálculo en UI M5 |
| E2E-16 | M8 suspende y luego finaliza beneficio | `SUSPENDED`/`FINALIZED`: futuras liquidaciones respetan vigencia; históricas no se reescriben | Fechas, descuentos antes/después, historial |
| E2E-17 | Crear solicitud exención en Rentas | `exemptionRequested`: M1 y M8 ven misma solicitud con `requestId`, `citizenId` y porcentaje correcto **si confirman ser receptores** | Copia evento y UI/perfil cuando exista soporte |
| E2E-18 | Aprobar/rechazar exención de Rentas | `updateExemptionStatus`: estado consistente en M1/M8, `REJECTED` sin exemptionId inventado | Contrato ratificado y vistas de 3 portales |
| E2E-19 | Cancelar deuda MUNICIPAL_TAX | `debtSettled` único, filtrado correcto M8/M4/M7 según acuerdo, UI M8 actualizada | Sólo cuando contrato transversal esté acordado |
| E2E-20 | Actualizar/bloquear ciudadano en M1 | `citizenUpdated`: M5 refleja vínculo y restricciones pertinentes sin mezclar IDs numéricos/UUID | Proyección canónica, acceso y vista usuario |
| E2E-21 | Solicitar/aprobar/rechazar plan de pagos M5 | Eventos `paymentPlanRequested` / `updatePaymentPlanStatus` proyectados en M1 **si M1 confirma recepción** | Flujo real con IDs y estados compartidos |

## 3. Fallos/resiliencia obligatorios

| Prueba | Procedimiento | Aceptación |
|---|---|---|
| Duplicado de evento | Entregar mismo `eventId` dos veces | Una sola obligación/ticket/beneficio y un solo efecto |
| Redelivery de publisher | Cortar confirmación y reintentar | Mismo `eventId` para un hecho ya construido; consumidor deduplica |
| Eventos fuera de orden | Resolver antes de recibir un PROGRESS antiguo; suspender antes de recibir APPROVED viejo | No retroceder estado ni duplicar acciones; política contractual documentada |
| Broker/Core temporalmente caído | Ejecutar pago/solicitud y reiniciar backend | Operación local persiste; evento queda pendiente, no se declara entregado; reintento eventual seguro |
| Reinicio **físico** | Parar proceso con outbox/DEFERRED y volver a iniciar | Evento sigue disponible, sin regenerar IDs ni montos |
| DLQ/error estructural | Inyectar payload imposible/versión no soportada | No aplicar cambio, trazabilidad, alerta, política de DLQ documentada |
| Correlación ausente | Retirar fuente `infractionConfirmed` o simular falta sin tocar producción | Pago se conserva, evento se aplaza, no fabricar correlationId |
| Fuente canónica ausente | Beneficio `citizenId` sin correspondencia M1 | Beneficio pendiente, no descuento a otro ciudadano |
| Pago dividido | Distribuir un pago entre 2+ deudas | Un `paymentRegistered` por imputación, montos suman pago, saldos correctos |
| Reversión parcial/concurrente | Doble petición o estado de cuota cambiando | Operación idempotente/serializada, sin reversión doble |
| UI eventual consistency | Deshabilitar comunicación unos instantes | UI no informa «confirmado en destino» antes de ack/proyección; refresco posterior correcto |

## 4. Formato de evidencia por prueba

Guardar como Markdown/CSV **sin datos personales reales**:

```text
Test ID:
Fecha y entorno:
Contrato/versión:
Módulos origen y destino:
Usuario de prueba (ID sintético):
Acción UI y datos de entrada:
ID de evento / correlación (de prueba):
Publicación local / aceptación Core / consumo remoto:
Estado BD origen antes→después:
Estado BD destino antes→después:
Estado visible portal origen/destino:
Resultado esperado / observado:
Capturas o logs sanitizados:
Estado PASA/FALLA/BLOQUEADO y enlace a issue:
```

## 5. Gates de aprobación

- **Antes de conectar Core:** contratos finales con versión, identidades y `originType` acordados; suites locales verdes.
- **Antes de prueba interportal:** despliegue accesible, credenciales de prueba, configuración de colas/ruteo, observabilidad/ack, datos sintéticos coherentes M1/M4/M7/M8.
- **Antes de presentar TP:** cada flujo crítico con evidencia de actualización visible entre portales, estados consistentes, tests de fallas principales, discrepancias documentadas y sin bloqueos ocultos.

## 6. Preguntas necesarias a los otros equipos (llevar a una reunión)

- **M1:** ¿cuál es UUID canónico ciudadano y equivalencia Long? ¿Qué cuatro eventos salientes M5 consume M1? ¿Cuándo está actualizado el envelope?
- **M4:** ¿qué formato/versiones de pagos/cancelaciones/reversiones acepta? ¿Campo `citizenId` extra? ¿`originId` refleja id de tasa/multa como propone doc octubre? ¿Correlación requerida?
- **M7:** ¿`conceptCode` es código de tributo libre o debe ser `TRAFFIC_FINE`? ¿Qué `citizenId` espera? ¿Reintentos/reversiones/idempotencia? ¿`debtId`/`paymentId` se conservan string?
- **M8:** ¿ratifica UUID `benefitId`/`applicationId`/`programId` y `requestId`? ¿`REJECTED` status de exención? ¿Filtra debtSettled por `MUNICIPAL_TAX` o consume todos?
- **Core:** ¿interfaz y contrato efectivos de publicación/recepción, JWT módulo, cola, routing y filtros? ¿ack de broker vs aceptación de consumidor, redelivery, DLQ, orden/reintentos y versionado?
