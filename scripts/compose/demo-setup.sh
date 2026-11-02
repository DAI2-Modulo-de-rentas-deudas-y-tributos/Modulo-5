#!/bin/sh
# Carga los contribuyentes ficticios de qa/seed-e2e-local.sql, crea un usuario DEMO por rol y
# datos de demostración por la API (configuración de tributos, deudas y una boleta).
# Corre en cada `docker compose up`; sólo crea lo que falta. Nunca imprime contraseñas.
set -eu

API=http://backend:8080/api/v1/dev-auth
APP=http://backend:8080/api/v1
PASSWORD="$RENTAS_DEMO_USERS_PASSWORD"

fail() { echo "ERROR: $*" >&2; exit 1; }

[ "${#PASSWORD}" -ge 8 ] || fail "RENTAS_DEMO_USERS_PASSWORD debe tener al menos 8 caracteres."
case "$PASSWORD" in *\"* | *\\*) fail "RENTAS_DEMO_USERS_PASSWORD no puede contener comillas dobles ni barras invertidas." ;; esac

sql() { psql -v ON_ERROR_STOP=1 -qtA -c "$1"; }
post() { wget -q -O - --header "Content-Type: application/json" "$@"; }

psql -v ON_ERROR_STOP=1 -q -f /seed/seed-e2e-local.sql > /dev/null
TAXPAYER_ID=$(sql "SELECT id FROM taxpayer_reference WHERE external_id = 'QA-M5-20261017-A'")

if [ "$(sql "SELECT count(*) FROM demo_user")" = "0" ]; then
  post --header "X-Demo-Bootstrap-Secret: ${RENTAS_DEMO_BOOTSTRAP_PASSWORD}" \
    --post-data "{\"username\":\"supervisor\",\"password\":\"${PASSWORD}\",\"displayName\":\"Supervisor Demo\"}" \
    "$API/bootstrap" > /dev/null || fail "no se pudo crear el usuario 'supervisor' (revisar RENTAS_DEMO_BOOTSTRAP_PASSWORD)."
fi

token_for() { # usuario
  login=$(post --post-data "{\"username\":\"$1\",\"password\":\"${PASSWORD}\"}" "$API/login") || return 1
  echo "$login" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p'
}

TOKEN=$(token_for supervisor) \
  || fail "no se pudo ingresar como 'supervisor'. Si cambiaste RENTAS_DEMO_USERS_PASSWORD después del primer arranque, borrá los datos con 'docker compose down -v' y volvé a levantar."

create_user() { # usuario rol "nombre visible" [taxpayerId]
  [ -n "$(sql "SELECT 1 FROM demo_user WHERE username = '$1'")" ] && return 0
  post --header "X-Demo-Session: ${TOKEN}" \
    --post-data "{\"username\":\"$1\",\"password\":\"${PASSWORD}\",\"displayName\":\"$3\",\"role\":\"$2\",\"taxpayerId\":${4:-null}}" \
    "$API/users" > /dev/null || fail "no se pudo crear el usuario '$1'."
}

create_user rentas RENTAS "Personal de Rentas Demo"
create_user cajero CASHIER "Cajero Demo"
create_user auditor AUDITOR "Auditor Demo"
create_user contribuyente TAXPAYER "QA Ciudadano A" "$TAXPAYER_ID"

echo "Usuarios DEMO listos: supervisor, rentas, cajero, auditor, contribuyente."

# --- Datos de demostración, por la API como lo haría el personal -----------------------------
# rentas carga y envía la configuración de cada tributo y supervisor la aprueba (no puede aprobar
# quien la envió). Las fechas se calculan al arrancar: siempre hay una deuda vencida y otras por
# vencer. Las deudas y la boleta se cargan una sola vez: si el contribuyente demo ya tiene una
# boleta, no se agrega nada más.
RENTAS_TOKEN=$(token_for rentas) || fail "no se pudo ingresar como 'rentas'."

api() { # token ruta json
  post --header "X-Demo-Session: $1" --post-data "$3" "$APP/$2" || fail "falló POST /api/v1/$2."
}

concept() { sql "SELECT id FROM tax_concept WHERE code = '$1'"; }
taxpayer() { sql "SELECT id FROM taxpayer_reference WHERE external_id = '$1'"; }

ensure_configuration() { # código campos-json
  id=$(sql "SELECT id FROM tax_configuration WHERE tax_concept_id = $(concept "$1") ORDER BY version DESC LIMIT 1")
  status=$(sql "SELECT status FROM tax_configuration WHERE id = ${id:-0}")
  if [ -z "$id" ]; then
    created=$(api "$RENTAS_TOKEN" tax-configurations "{\"taxConceptId\":$(concept "$1"),$2,\"partialPaymentAllowed\":true,\"paymentPlanAllowed\":true,\"validFrom\":\"$VALID_FROM\"}")
    id=$(echo "$created" | sed -n 's/^{"id":\([0-9]*\).*/\1/p')
    status=DRAFT
  fi
  if [ "$status" = DRAFT ]; then
    api "$RENTAS_TOKEN" "tax-configurations/$id/submit" "{}" > /dev/null
    status=PENDING_APPROVAL
  fi
  if [ "$status" = PENDING_APPROVAL ]; then
    api "$TOKEN" "tax-configurations/$id/approve" "{\"observation\":\"Configuración de demostración\"}" > /dev/null
  fi
}

ensure_liquidation() { # externalId-contribuyente código período base vencimiento
  payer=$(taxpayer "$1")
  [ -n "$(sql "SELECT 1 FROM liquidation WHERE taxpayer_id = $payer AND tax_concept_id = $(concept "$2") AND period = '$3'")" ] \
    || api "$RENTAS_TOKEN" liquidations "{\"taxpayerId\":$payer,\"taxConceptId\":$(concept "$2"),\"period\":\"$3\",\"taxableBase\":$4,\"dueDate\":\"$5\"}" > /dev/null
}

VALID_FROM="$(($(date +%Y) - 1))-01-01"
read -r OLD_PERIOD OLD_DUE PERIOD DUE <<EOF
$(sql "SELECT to_char(now() - interval '2 months', 'YYYY-MM'),
              to_char(date_trunc('month', now()) - interval '1 month' + interval '9 days', 'YYYY-MM-DD'),
              to_char(now() - interval '1 month', 'YYYY-MM'),
              to_char(now() + interval '10 days', 'YYYY-MM-DD')" | tr '|' ' ')
EOF

ensure_configuration ABL '"calculationType":"PERCENTAGE","rate":0.15,"minimumAmount":5000'
ensure_configuration TASA_SERVICIOS '"calculationType":"FIXED","fixedAmount":12000'
ensure_configuration PATENTE '"calculationType":"PERCENTAGE","rate":0.4'

[ -n "$(sql "SELECT 1 FROM payment_plan_configuration LIMIT 1")" ] \
  || api "$RENTAS_TOKEN" payment-plan-configurations "{\"minimumInstallments\":2,\"maximumInstallments\":12,\"minimumDownPaymentPercentage\":10,\"interestRate\":2.5,\"graceDays\":10,\"maxOverdueInstallments\":3,\"partialInstallmentPaymentAllowed\":false,\"refinancingAllowed\":true,\"maxRefinancingCount\":1,\"validFrom\":\"$VALID_FROM\",\"active\":true}" > /dev/null

if [ -z "$(sql "SELECT 1 FROM bill WHERE taxpayer_id = $TAXPAYER_ID LIMIT 1")" ]; then
  ensure_liquidation QA-M5-20261017-A ABL "$OLD_PERIOD" 18500000 "$OLD_DUE"
  ensure_liquidation QA-M5-20261017-A ABL "$PERIOD" 18500000 "$DUE"
  ensure_liquidation QA-M5-20261017-A TASA_SERVICIOS "$PERIOD" 18500000 "$DUE"
  ensure_liquidation QA-M5-20261017-B ABL "$PERIOD" 9200000 "$DUE"
  ensure_liquidation QA-M5-20261017-B PATENTE "$PERIOD" 7500000 "$DUE"
  ensure_liquidation QA-M5-20261017-ORG TASA_SERVICIOS "$PERIOD" 32000000 "$DUE"

  DEBT_IDS=$(sql "SELECT string_agg(d.id::text, ',' ORDER BY d.id) FROM debt d JOIN liquidation l ON l.id = d.liquidation_id
                  WHERE l.taxpayer_id = $TAXPAYER_ID AND l.period = '$PERIOD'")
  api "$RENTAS_TOKEN" bills "{\"taxpayerId\":$TAXPAYER_ID,\"debtIds\":[$DEBT_IDS],\"dueDate\":\"$DUE\"}" > /dev/null
fi

echo "Datos de demo listos: $(sql "SELECT count(*) FROM tax_configuration WHERE status = 'ACTIVE'") configuraciones activas, $(sql "SELECT count(*) FROM debt") deudas, $(sql "SELECT count(*) FROM bill") boleta(s)."
