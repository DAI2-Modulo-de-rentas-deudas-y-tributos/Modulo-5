#!/bin/sh
# Declara en el RabbitMQ local la topología que en los ambientes reales pertenece a Core (M9).
# Corre en cada `docker compose up`; las declaraciones son idempotentes.
set -eu

api() {
  curl -fsS --retry 15 --retry-connrefused --retry-delay 2 \
    -u "${RABBITMQ_USER}:${RABBITMQ_PASSWORD}" -H "Content-Type: application/json" \
    -X "$1" "http://rabbitmq:15672/api/$2" -d "$3" > /dev/null
}

# Salida de M5: el outbox publica en muni.inbox; la cola permite ver lo publicado en la consola.
api PUT exchanges/%2F/muni.inbox '{"type":"fanout","durable":true}'
api PUT queues/%2F/q.m5.publicados '{"durable":true}'
api POST bindings/%2F/e/muni.inbox/q/q.m5.publicados '{}'

# Entrada de M5: lo que el consumidor rechaza (NACK sin requeue) termina en q.rentas.dlq.
api PUT exchanges/%2F/rentas.dlx '{"type":"fanout","durable":true}'
api PUT queues/%2F/q.rentas.dlq '{"durable":true}'
api POST bindings/%2F/e/rentas.dlx/q/q.rentas.dlq '{}'
api PUT queues/%2F/q.rentas '{"durable":true,"arguments":{"x-dead-letter-exchange":"rentas.dlx"}}'

echo "RabbitMQ local listo: muni.inbox -> q.m5.publicados, q.rentas -> q.rentas.dlq."
