#!/bin/sh
# Verifica el sistema levantado con `docker compose up -d --build`: el frontend responde con sus
# cabeceras de seguridad y cada usuario DEMO ingresa a través de su proxy. Desde la raíz del repo:
#   sh scripts/compose/smoke-test.sh
# Lee el .env de la raíz; nunca imprime la contraseña.
set -eu

env_value() { sed -n "s/^$1=//p" .env | tr -d '\r'; }

SECRET=$(env_value RENTAS_DEMO_USERS_PASSWORD)
[ -n "$SECRET" ] || { echo "ERROR: falta RENTAS_DEMO_USERS_PASSWORD en .env." >&2; exit 1; }
PORT=$(env_value FRONTEND_PORT)
FRONTEND="http://localhost:${PORT:-5173}"

# `up -d` vuelve cuando el contenedor del frontend arranca, no cuando ya escucha.
curl --fail --silent --show-error --retry 30 --retry-delay 2 --retry-all-errors "$FRONTEND/" | grep -q 'id="root"' \
  || { echo "ERROR: el frontend no responde en $FRONTEND." >&2; exit 1; }

HEADERS=$(curl --fail --silent --show-error --head "$FRONTEND/")
for header in Content-Security-Policy X-Frame-Options X-Content-Type-Options Referrer-Policy; do
  echo "$HEADERS" | grep -qi "^$header:" || { echo "ERROR: el frontend no envía la cabecera $header." >&2; exit 1; }
done
echo "Cabeceras de seguridad presentes."

for user in supervisor rentas cajero auditor contribuyente; do
  curl --fail --silent --show-error --header "Content-Type: application/json" \
    --data "{\"username\":\"$user\",\"password\":\"$SECRET\"}" "$FRONTEND/api/v1/dev-auth/login" | grep -q '"token"' \
    || { echo "ERROR: no se pudo ingresar como '$user'." >&2; exit 1; }
  echo "Ingreso correcto: $user"
done

echo "Sistema verificado en $FRONTEND."
