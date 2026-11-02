# M5 ↔ M7 V4.1: validación local (30/10/2026)

Fuentes: `TPO-  EventosM7-V4.1.pdf` y confirmación posterior
`M5_a_M7_Contrato_Eventos_UUID_Aprobados_2026-10-27.md`.
La confirmación de M7 comprende los UUID persistentes de pago y deuda,
`TRAFFIC_INFRACTION`, `citizenId` canónico u omitido y la referencia exacta
al UUID de infracción. No acredita recepción mediante Core.

## Persistencia comprobada

Una consulta de solo lectura a PostgreSQL local `rentas` (17.11) mostró Flyway
en V28, V25 y V27 exitosas, 6 deudas con 6 UUID distintos y 5 pagos con 5 UUID
distintos, todos no nulos. Las claves primarias `Long` siguen en uso. No se
ejecutó ninguna migración ni se modificaron datos de `rentas` en esta etapa.

## Comportamiento local

- `infractionConfirmed` guarda en `integration_event_log` el envelope original,
  incluido `eventId`, `correlationId`, datos administrativos e `infractionId`.
  La obligación externa retiene el `infractionId` textual original y la deuda
  recibe un UUID propio de M5. Los duplicados por evento o infracción no crean
  otra deuda.
- Los hechos de pago, cancelación total y reversión usan los UUID persistentes
  de `payment` y `debt`, y el mismo `infractionId` de M7 en `originId` e
  `infractionId`. Cada imputación de un pago distribuido conserva su importe y
  saldo; las cuotas usan el registro exacto de distribución de capital.
- El hilo económico sólo se recupera del `infractionConfirmed` persistido si
  coinciden `eventId`, tipo, versión, emisor e `infractionId`. Los pagos locales
  independientes dejan `causationId` en `null` y preservan el `correlationId`
  original. Si la prueba de origen no alcanza, el hecho se aplaza con una
  instantánea del movimiento; no se recalcula desde el saldo actual.
- Una referencia de infracción histórica que no sea UUID no produce un evento
  preparado. El publicador aparta cualquier fila de Tránsito con origen,
  importes o saldos que incumplan V4.1. Los reintentos reutilizan el JSON y
  `eventId` guardados en la fila del outbox.
- `debtSettled` municipal sigue aplazado; la corrección de M7 no modifica los
  payloads compartidos con M4 o M8.

## JSON de prueba y límites

[`qa/m7-v41-local-serialized.ndjson`](../../qa/m7-v41-local-serialized.ndjson)
contiene dos `paymentRegistered`, un `debtSettled` y un `paymentReversed`
serializados realmente por M5 durante una prueba local. Son **fixtures con
identificadores sintéticos**; no proceden de M7 ni fueron entregados a Core.
El outbox preparado y `LOCAL_ONLY` tampoco representan confirmación de consumo.

Pruebas ejecutadas con H2 y PostgreSQL temporal de Testcontainers: **109
focalizadas** y **584 en la suite completa**, sin fallas, errores ni omisiones.
El frontend no se modificó ni se volvió a ejecutar en esta etapa.

La prueba E2E queda pendiente: M7 debe publicar una infracción real mediante
Core, M5 debe consumirla, preparar los hechos económicos y M7 debe confirmar
su recepción y deduplicación. No se habilitó RabbitMQ ni el transporte real.
