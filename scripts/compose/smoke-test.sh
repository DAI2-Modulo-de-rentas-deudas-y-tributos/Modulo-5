#!/bin/sh
# Verifica el sistema levantado con `docker compose up -d --build`: el frontend responde con sus
# cabeceras de seguridad, cada usuario DEMO ingresa a través de su proxy y ningún log de Compose
# muestra contraseñas. Desde la raíz del repo:
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

# Ninguna credencial puede aparecer en los logs de ningún servicio (SCRUM-818, Eje D): ni la que
# genera Spring ni los valores del .env. Va después de los ingresos para cubrirlos también.
LOGS=$(docker compose logs --no-color)
if printf '%s' "$LOGS" | grep -qi 'generated security password'; then
  echo "ERROR: los logs muestran una contraseña generada por Spring." >&2; exit 1
fi
for name in POSTGRES_PASSWORD RABBITMQ_PASSWORD RENTAS_DEMO_BOOTSTRAP_PASSWORD RENTAS_DEMO_USERS_PASSWORD; do
  value=$(env_value "$name")
  if [ -n "$value" ] && printf '%s' "$LOGS" | grep -qF -- "$value"; then
    echo "ERROR: el valor de $name aparece en los logs de Compose." >&2; exit 1
  fi
done
echo "Los logs no muestran credenciales."

echo "Sistema verificado en $FRONTEND."
