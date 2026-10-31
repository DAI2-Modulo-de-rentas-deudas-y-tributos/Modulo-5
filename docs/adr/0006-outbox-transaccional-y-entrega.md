# ADR 0006: Outbox transaccional y estado de entrega

- **Estado:** Aceptado para persistencia local; adaptador/ACK externo pendiente.
- **Fecha:** 2026-10-26.

## Contexto

M5 debe persistir cambios de negocio y mensajes salientes de forma que no quede un evento local sin su transacción económica, ni una operación confirmada sin la intención de publicación correspondiente.

## Problema

Publicar directamente al broker dentro de una operación HTTP acopla disponibilidad externa con la transacción de negocio y puede dejar resultados inconsistentes si falla uno de los lados.

## Decisión

Persistir la intención saliente en la tabla Outbox dentro del mismo límite transaccional que el cambio de dominio. Procesarla asincrónicamente desde un publisher. La persistencia local de Outbox no equivale a entrega confirmada por Core ni por el consumidor.

## Implementación observada

- `backend/src/main/java/ar/gob/municipalidad/rentas/EventosDeDominio.java`: interfaz de eventos del dominio.
- `backend/src/main/java/ar/gob/municipalidad/rentas/OutboxEventosDeDominio.java`: adaptador que serializa eventos del dominio en registros `OutboxEvent` pendientes.
- `backend/src/main/java/ar/gob/municipalidad/rentas/integration/producer/ConfirmedIntegrationOutbox.java`: prepara eventos de integración, UUID M5 e idempotencia; persiste snapshots diferidos cuando falta información externa.
- `backend/src/main/java/ar/gob/municipalidad/rentas/IntegrationServices.java`: procesa Outbox, aplica validaciones/reintentos y actualiza estado local.
- `backend/src/test/java/ar/gob/municipalidad/rentas/DomainEventOutboxTransactionTests.java`, `EconomicOutboxGuardTests.java` y `PhysicalRestartEconomicEventTest.java`: cubren rollback, guardas de contrato y recuperación local después de reiniciar el proceso, según el test ejecutado.

## Contratos y límites de integración

- Se conserva el envelope Core acordado en el código y la documentación; este ADR no modifica contratos compartidos.
- El adaptador local `LocalLogEventPublisher` sólo registra que recibió la solicitud local; no es RabbitMQ, no demuestra ACK ni interoperabilidad real.
- Un estado `PUBLISHED` debe interpretarse según el adaptador configurado; en esta implementación no es evidencia suficiente de recepción externa.
- Identidad canónica M1 y contratos económicos externos aún sujetos a confirmación no se inventan aquí.

## Alternativas consideradas

1. Publicación síncrona en el request: descartada por acoplamiento y ventana de inconsistencia.
2. Marcar entrega confirmada al persistir el outbox: descartada; intención local y ACK remoto son estados diferentes.
3. Activar RabbitMQ/Core en esta etapa: descartada porque requiere acuerdos y entorno autorizados externos.

## Consecuencias

**Positivas:** intención durable local, reintento tras fallas y persistencia alineada con la transacción de negocio.

**Costos:** el outbox requiere retención/operación y el adaptador definitivo debe especificar reintentos, ACK, routing y DLQ.

**Pendiente:** prueba conjunta con Core y consumidores reales, ACK verificable, routing y semántica de DLQ. La ausencia de esos sistemas bloquea NIVEL 3; no invalida las pruebas locales y contractuales de M5.
