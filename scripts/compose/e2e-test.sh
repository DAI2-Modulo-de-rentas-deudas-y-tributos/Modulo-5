#!/bin/sh
# Pruebas de punta a punta con Playwright (frontend/e2e) contra el sistema levantado con
# `docker compose up -d --build`. Desde la raíz del repo, con `npm ci` hecho en frontend/:
#   sh scripts/compose/e2e-test.sh
# Lee la contraseña de los usuarios DEMO del .env; nunca la imprime.
set -eu

E2E_DEMO_PASSWORD=$(sed -n 's/^RENTAS_DEMO_USERS_PASSWORD=//p' .env | tr -d '\r')
[ -n "$E2E_DEMO_PASSWORD" ] || { echo "ERROR: falta RENTAS_DEMO_USERS_PASSWORD en .env." >&2; exit 1; }
export E2E_DEMO_PASSWORD

cd frontend
exec ./node_modules/.bin/playwright test "$@"
