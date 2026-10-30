# Integración local M5 ↔ M4 Habilitaciones

Referencia contractual: `Contrato_Eventos_M4_Integral.docx`, facilitado por M4 fuera del repositorio, y aclaraciones recibidas el 30/10. Esta validación usa sólo H2 y PostgreSQL efímero; no acredita entrega a Core ni consumo por M4.

| Evento | Dirección | Estado local | Condición pendiente |
| --- | --- | --- | --- |
| `permitFeeGenerated` | M4 → M5 | DTO validado, hecho original e hilo persistidos, obligación y deuda creadas sin duplicación por `data.id` | Referencia M1 por CUIT, concepto `PERMIT_FEE` activo y plazo M5 explícito |
| `commercialFineGenerated` | M4 → M5 | Igual; se conservan `actId`, `sourceViolationId`, `externalRef`, `sourceModule` de data y `decision` en el evento recibido | Referencia M1 por CUIT, concepto `COMMERCIAL_FINE` activo y plazo M5 explícito |
| `paymentRegistered` | M5 → M4 | Preparado por imputación real con UUID persistentes de pago y deuda, saldo de ese movimiento y `originId` de M4 | M4 debe comenzar a publicar UUID y hay que probar Core extremo a extremo |
| `debtSettled` | M5 → M4 | Preparado una sola vez por cancelación total si existe `citizenId` canónico verificado | Para organizaciones sin ese UUID se aplaza; M4 debe confirmar la variante nula u omitida |
| `paymentReversed` | M5 → M4 | Preparado por reversión ejecutada, después del pago registrado, con monto y saldo de la reversión | Entrega real pendiente |

## Identidad y vencimiento

`data.id` de M4 se almacena sin sustitución en `external_obligation.external_reference_id`; pasa a `originId` de las publicaciones económicas. `permitApplicationId`, `establishmentId`, `actId` y `sourceViolationId` quedan en el JSON original de `integration_event_log`. CUIT sigue siendo un string de once dígitos y sólo sirve para encontrar una organización M1 ya proyectada. M5 nunca lo convierte en `citizenId`. La deuda conserva su PK Long y su UUID `integration_id`; los pagos usan su propio UUID persistente.

`tax_configuration` sólo define vigencia de una configuración económica; no tiene una regla de plazo de pago. Por eso el adaptador M4 requiere, además de un concepto activo, una configuración explícita por tipo:

```properties
rentas.m4.due-days.permit-fee=<plazo aprobado para tasas>
rentas.m4.due-days.commercial-fine=<plazo aprobado para multas>
```

Sin un valor entero no negativo para el tipo, el evento queda en `integration_event_log` con `M4_DUE_DATE_POLICY_PENDING`; no se crea obligación cobrable ni deuda. La política local usa la fecha de `occurredAt` en `America/Argentina/Buenos_Aires` más los días configurados; así un reproceso conserva el día de referencia aunque el lector JSON normalice el offset. M4 y Rentas deben aprobar los plazos y la fecha de inicio antes de usarla en la prueba real. El vencimiento de una deuda ya creada no cambia por reintentos. Una excepción futura exige una regla local explícita, no un campo extra en M4.

El concepto se determina por el evento: `PERMIT_FEE` o `COMMERCIAL_FINE`. No se exige `conceptCode` al emisor. Si el concepto activo falta, el evento queda pendiente de configuración. Los valores históricos `FEE-`, `FINE-`, `PA-` y `EST-` quedan para revisión: no se convierten en UUID calculados ni se publican como referencias nuevas.

Consulta de sólo lectura a `rentas` el 30/10: `external_obligation` tiene **0 filas** cuyo `source_module` sea `M4` o `habilitaciones`. No hay allí obligaciones históricas M4 que convertir; la protección del código sigue siendo necesaria para otras copias o futuros datos.

## Correlación, causalidad y entrega

El JSON entrante, incluido `correlationId` y cualquier `causationId` auténtico, queda en `integration_event_log`. Para un pago M5 posterior se conserva el hilo original. Como el pago es un movimiento nuevo y no una respuesta inmediata al hecho administrativo de M4, `causationId` es `null` en el JSON local del outbox; no se atribuye causalidad ficticia al evento de la tasa o multa. El contrato de M4 pide omitir atributos nulos del envelope: hay que verificar con Core si los elimina al entregar, como informó para los otros módulos, antes del E2E. Cada hecho económico tiene `eventId` propio, estable en el outbox y en sus reintentos. Los eventos aplazados conservan una instantánea del importe y saldo del movimiento.

Un evento económico M4 sin UUID de origen válido, sin correlación original o sin información suficiente queda aplazado para revisión. Las cancelaciones M4 sin `citizenId` canónico también quedan aplazadas; una conciliación no las libera sólo porque reaparezca la correlación. Las cancelaciones M7 con `citizenId` ausente siguen su tratamiento aprobado. Actualización posterior a esta validación local: M8 aceptó `MUNICIPAL_TAX` y `debtSettled` municipal ya se prepara cuando existe un UUID canónico verificado; sigue aplazado si falta. El estado local `LOCAL_ONLY` o `BROKER_CONFIRMED` no significa consumo por M4.

Los ocho JSON completos de [qa/m4-local-serialized.ndjson](../../qa/m4-local-serialized.ndjson) proceden de filas creadas por el productor en una prueba local. Son datos sintéticos de un contribuyente de prueba con vínculo canónico suministrado por el fixture para demostrar la variante de cancelación; no representan una organización real de M4 ni eventos entregados a Core. Cada origen tiene pago de 40 sobre 100, pago de 60 que deja saldo cero, cancelación y reversión de 40 que reabre saldo 40.

## Pendientes para la prueba M5–M4

1. M4 debe publicar `data.id`, `permitApplicationId` y `establishmentId` como UUID persistentes y estables; confirmar que un mismo hecho sancionatorio mantiene siempre el mismo `data.id`.
2. Configurar en M5 conceptos activos `PERMIT_FEE` y `COMMERCIAL_FINE` y aprobar explícitamente plazos y fecha base del vencimiento.
3. Acordar la variante de `debtSettled.citizenId` para organizaciones. Hasta entonces, no intentar publicar esas cancelaciones.
4. En un entorno de pruebas compartido, recibir un evento real de cada tipo, verificar CUIT/proyección M1, UUID de origen, importe, trazabilidad M6 y vencimiento calculado.
5. Reenviar el mismo `eventId` y el mismo `data.id` con otro `eventId`; comprobar una sola obligación y una sola deuda. Un `eventId` con contenido distinto debe rechazarse.
6. Registrar pago parcial, pago final y reversión en M5. Comparar el JSON del outbox con el contrato M4: IDs, `originId`, importes por imputación, saldos, fechas, `correlationId` y causalidad.
7. Verificar recepción y deduplicación en M4 a través de Core, incluida entrega fuera de orden. Una aceptación HTTP o confirmación del broker no prueba que M4 procesó el evento.
8. Conciliar por separado referencias históricas prefijadas; no convertirlas ni reenviarlas automáticamente.

No se cambiaron scripts Flyway ni se ejecutaron migraciones. No se configuró ni usó RabbitMQ/Core.

## Pruebas ejecutadas

El 30/10, la suite completa `mvn test` terminó con **595 pruebas, 0 fallos, 0 errores, 0 omitidas y BUILD SUCCESS**. Incluye los casos M4 focalizados, PostgreSQL efímero, regresiones M1/M2/M7/M8 y las pruebas locales del adaptador de RabbitMQ con contenedor temporal. No se envió ningún evento al Core compartido. Los fixtures JSON se copiaron del outbox de prueba tras esta corrida.
