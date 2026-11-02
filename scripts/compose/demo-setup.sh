#!/bin/sh
# Carga los contribuyentes ficticios de qa/seed-e2e-local.sql y crea un usuario DEMO por rol.
# Corre en cada `docker compose up`; sólo crea lo que falta. Nunca imprime contraseñas.
set -eu

API=http://backend:8080/api/v1/dev-auth
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

LOGIN=$(post --post-data "{\"username\":\"supervisor\",\"password\":\"${PASSWORD}\"}" "$API/login") \
  || fail "no se pudo ingresar como 'supervisor'. Si cambiaste RENTAS_DEMO_USERS_PASSWORD después del primer arranque, borrá los datos con 'docker compose down -v' y volvé a levantar."
TOKEN=$(echo "$LOGIN" | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')

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
