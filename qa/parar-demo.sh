#!/usr/bin/env bash
# Para el stack de M5 Rentas (frontend + backend + PostgreSQL) sin tocar otros proyectos.
# Uso: ./qa/parar-demo.sh

set -uo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

echo "== Frontend =="
if pkill -f "$REPO_ROOT/frontend/node_modules/.bin/vite"; then
  echo "Frontend detenido."
else
  echo "Frontend no estaba corriendo."
fi

echo "== Backend =="
if pkill -f "ar.gob.municipalidad.rentas.RentasApplication"; then
  echo "Backend detenido."
else
  echo "Backend no estaba corriendo."
fi

echo "== PostgreSQL =="
docker compose stop postgres

echo "== Listo =="
echo "(los datos de Postgres quedan intactos; ./qa/levantar-demo.sh vuelve a levantar todo)"
