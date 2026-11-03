#!/bin/sh
# Resiliencia de la mensajería (Eje C) sobre el sistema levantado con `docker compose up -d --build`:
#   1. Mensaje duplicado: el mismo permitFeeGenerated enviado dos veces genera una sola obligación.
#   2. Mensaje inválido: termina en la cola de mensajes muertos q.rentas.dlq.
#   3. Broker caído: con RabbitMQ apagado la API sigue registrando pagos; al volver, el outbox
#      publica el evento pendiente sin perderlo.
# Desde la raíz del repo:  sh scripts/compose/resilience-test.sh
# Lee el .env de la raíz; nunca imprime contraseñas ni sesiones.
set -eu

env_value() { sed -n "s/^$1=//p" .env | tr -d '\r'; }
fail() { echo "ERROR: $*" >&2; exit 1; }

RABBIT_USER=$(env_value RABBITMQ_USER)
RABBIT_USER=${RABBIT_USER:-m5-local}
RABBIT_PASSWORD=$(env_value RABBITMQ_PASSWORD)
PASSWORD=$(env_value RENTAS_DEMO_USERS_PASSWORD)
API="http://localhost:${BACKEND_PORT:-8080}/api/v1"
MGMT="http://localhost:${RABBITMQ_MANAGEMENT_PORT:-15672}/api"

# UUID v4 (el consumidor exige esa versión en eventId).
uuid() { od -An -N16 -tx1 /dev/urandom | tr -d ' \n' | sed -E 's/^(.{8})(.{4}).(.{3}).(.{3})(.{12})$/\1-\2-4\3-a\4-\5/'; }
sql() { docker compose exec -T postgres sh -c 'psql -U "$POSTGRES_USER" -d "$POSTGRES_DB" -At'; }
mgmt() { curl --fail --silent --show-error --user "$RABBIT_USER:$RABBIT_PASSWORD" "$@"; }
# Mensajes listos más sin confirmar, al instante (las estadísticas de la API de administración se
# muestrean cada 5 s y pueden estar atrasadas).
queue_messages() {
  docker compose exec -T rabbitmq rabbitmqctl list_queues --quiet name messages | awk -v queue="$1" '$1 == queue { print $2 }'
}
wait_until() { # mensaje de error, función que tiene que terminar bien
  attempts=0
  until "$2"; do
    attempts=$((attempts + 1))
    [ "$attempts" -lt 60 ] || fail "$1"
    sleep 2
  done
}

publish() { # cuerpo JSON del mensaje, publicado directo en q.rentas
  body=$(printf '%s' "$1" | sed 's/\\/\\\\/g; s/"/\\"/g')
  mgmt --header "Content-Type: application/json" \
    --data "{\"properties\":{\"delivery_mode\":2,\"content_type\":\"application/json\"},\"routing_key\":\"q.rentas\",\"payload\":\"$body\",\"payload_encoding\":\"string\"}" \
    "$MGMT/exchanges/%2F/amq.default/publish" | grep -q '"routed":true' || fail "RabbitMQ no enrutó el mensaje a q.rentas."
}

# --- 1. Mensaje duplicado -------------------------------------------------------------------
FEE_ID=$(uuid)
EVENT="{\"eventId\":\"$(uuid)\",\"eventType\":\"permitFeeGenerated\",\"eventVersion\":\"1.0\",\"occurredAt\":\"$(date -u +%Y-%m-%dT%H:%M:%SZ)\",\"sourceModule\":\"habilitaciones\",\"correlationId\":\"$(uuid)\",\"data\":{\"id\":\"$FEE_ID\",\"permitApplicationId\":\"$(uuid)\",\"establishmentId\":\"$(uuid)\",\"cuit\":\"30999999999\",\"amount\":5000,\"currency\":\"ARS\"}}"
publish "$EVENT"
publish "$EVENT"
obligations() { echo "SELECT count(*) FROM external_obligation WHERE external_reference_id = '$FEE_ID'" | sql; }
obligation_created() { [ "$(obligations)" -gt 0 ]; }
wait_until "el permitFeeGenerated no generó la obligación." obligation_created
inbox_empty() { [ "$(queue_messages q.rentas)" = 0 ]; }
wait_until "q.rentas no terminó de consumir los mensajes." inbox_empty
OBLIGATIONS=$(obligations)
[ "$OBLIGATIONS" = 1 ] || fail "el permitFeeGenerated duplicado generó $OBLIGATIONS obligaciones (se esperaba 1)."
DEBT_ID=$(echo "SELECT d.id FROM debt d JOIN external_obligation o ON o.id = d.external_obligation_id WHERE o.external_reference_id = '$FEE_ID'" | sql)
[ -n "$DEBT_ID" ] || fail "el permitFeeGenerated no generó la deuda."
echo "Mensaje duplicado: una sola obligación y una sola deuda (#$DEBT_ID)."

# --- 2. Mensaje inválido --------------------------------------------------------------------
DEAD_BEFORE=$(queue_messages q.rentas.dlq)
publish '{"evento":"no cumple el envelope"}'
dead_letter_arrived() { [ "$(queue_messages q.rentas.dlq)" -gt "$DEAD_BEFORE" ]; }
wait_until "el mensaje inválido no llegó a q.rentas.dlq." dead_letter_arrived
echo "Mensaje inválido: quedó en q.rentas.dlq para su reproceso."

# --- 3. Broker caído y recuperado -----------------------------------------------------------
TAXPAYER_ID=$(echo "SELECT taxpayer_id FROM debt WHERE id = $DEBT_ID" | sql)
PUBLISHED_BEFORE=$(queue_messages q.m5.publicados)
TOKEN=$(curl --fail --silent --show-error --header "Content-Type: application/json" \
  --data "{\"username\":\"cajero\",\"password\":\"$PASSWORD\"}" "$API/dev-auth/login" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || fail "no se pudo ingresar como 'cajero'."

docker compose stop rabbitmq
STATUS=$(curl --silent --output /dev/null --write-out '%{http_code}' --header "X-Demo-Session: $TOKEN" \
  --header "Content-Type: application/json" \
  --data "{\"taxpayerId\":$TAXPAYER_ID,\"paymentMethod\":\"CASH\",\"amount\":100,\"allocations\":[{\"debtId\":$DEBT_ID,\"amount\":100}]}" \
  "$API/payments")
[ "$STATUS" = 201 ] || fail "con el broker caído el pago respondió $STATUS (se esperaba 201)."
pending_outbox() { echo "SELECT count(*) FROM outbox_event WHERE target_module <> 'PLATFORM' AND status IN ('PENDING', 'FAILED')" | sql; }
outbox_waiting() { [ "$(pending_outbox)" -gt 0 ]; }
wait_until "el evento del pago no quedó pendiente en el outbox." outbox_waiting
echo "Broker caído: el pago se registró (201) y su evento quedó pendiente en el outbox."

docker compose start rabbitmq
event_published() {
  published=$(queue_messages q.m5.publicados 2>/dev/null || true)  # vacío mientras RabbitMQ arranca
  [ "${published:-0}" -gt "$PUBLISHED_BEFORE" ] && [ "$(pending_outbox)" = 0 ]
}
wait_until "al volver el broker el outbox no publicó el evento pendiente." event_published
LOST=$(echo "SELECT count(*) FROM outbox_event WHERE status = 'DEAD_LETTER'" | sql)
[ "$LOST" = 0 ] || fail "hay $LOST eventos del outbox en DEAD_LETTER."
echo "Broker recuperado: el outbox publicó el evento pendiente; ninguno se perdió."
