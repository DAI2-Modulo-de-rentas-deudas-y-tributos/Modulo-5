#!/bin/sh
# Completa el .env como indica el README, con secretos aleatorios enmascarados en GitHub Actions.
# Lo usan los workflows que levantan el sistema con docker compose.
set -eu

cp .env.example .env
for name in POSTGRES_PASSWORD RABBITMQ_PASSWORD RENTAS_DEMO_BOOTSTRAP_PASSWORD RENTAS_DEMO_USERS_PASSWORD; do
  value=$(openssl rand -hex 16)
  [ -z "${GITHUB_ACTIONS:-}" ] || echo "::add-mask::$value"
  grep -q "^$name=$" .env || { echo "ERROR: .env.example no tiene $name vacío." >&2; exit 1; }
  sed -i "s/^$name=$/$name=$value/" .env
done
