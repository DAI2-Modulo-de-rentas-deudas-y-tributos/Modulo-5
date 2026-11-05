# Reintentos de eventos entrantes en M5

El consumidor de `q.rentas` permanece deshabilitado por defecto (`CORE_RABBITMQ_CONSUMER_ENABLED=false`). La topología de reintentos añadida por `scripts/compose/broker-setup.sh` es exclusivamente local; no declara ni modifica colas de Core/M9.

## Política local

- Envelope/payload inválido o error de negocio permanente: `NACK` sin requeue; RabbitMQ local enruta el mensaje a `q.rentas.dlq`.
- Error transitorio (por ejemplo, base no disponible): publicación persistente a `q.rentas.retry.1` (TTL 1 s), `q.rentas.retry.2` (TTL 5 s) y `q.rentas.retry.3` (TTL 10 s). El mensaje original se confirma sólo después de recibir publisher-confirm y comprobar que fue enrutado.
- M5 selecciona el siguiente paso mediante el header de broker `x-last-death-queue`; RabbitMQ lo actualiza al expirar el mensaje en cada cola de espera.
- Después de la cuarta ejecución total (entrega original + tres reintentos), un nuevo fallo va a `q.rentas.dlq`.
- Si la publicación de reintento no recibe confirmación o no se enruta, el original se envía directamente a la DLQ para no confirmarlo como entregado. Un fallo entre publisher-confirm y ACK puede producir una redelivery duplicada; el consumidor conserva el `eventId` y el Inbox debe deduplicarlo.
- Para consultar la DLQ local, revisar el cuerpo del mensaje en `q.rentas.dlq`; el envelope contiene `eventId`. Las métricas/confirmaciones de RabbitMQ no prueban procesamiento por otro módulo.

La secuencia local se prueba con RabbitMQ 4.3 real en Testcontainers. En un entorno de Core/M9 se requiere acordar previamente nombres de exchange/colas, TTLs, DLX, routing, permisos y quién administra la topología. No activar el consumidor externo basándose sólo en estos valores locales.

## Configuración

`rentas.core.amqp.retry-exchange` y `rentas.core.amqp.retry-queue-1` a `retry-queue-3` admiten configuración externa mediante `CORE_RABBITMQ_RETRY_EXCHANGE` y `CORE_RABBITMQ_RETRY_QUEUE_1` a `_3`. El timeout de publisher-confirm se configura en milisegundos mediante `CORE_RABBITMQ_CONFIRM_TIMEOUT_MS` (5.000 por defecto). Deben coincidir con la topología del broker autorizado.
