#!/bin/sh
# Pruebas de contrato (Eje B): valida contra el OpenAPI publicado por el backend las respuestas de
# todas las operaciones, con datos válidos e inválidos generados por Schemathesis. Falla ante un 5xx,
# un estado o Content-Type no documentado o un cuerpo que no cumple el schema. Desde la raíz del repo,
# con el sistema levantado por `docker compose up -d --build`:
#   sh scripts/compose/contract-test.sh
# En Docker Desktop (Windows/macOS) usar API_URL=http://host.docker.internal:8080 DOCKER_NETWORK=bridge
# (y en Git Bash, MSYS_NO_PATHCONV=1 para que no reescriba la ruta /reports).
# Lee el .env de la raíz; nunca imprime la contraseña ni la sesión.
set -eu

API_URL="${API_URL:-http://localhost:${BACKEND_PORT:-8080}}"
REPORT_DIR="${REPORT_DIR:-schemathesis-report}"
PASSWORD=$(sed -n 's/^RENTAS_DEMO_USERS_PASSWORD=//p' .env | tr -d '\r')
[ -n "$PASSWORD" ] || { echo "ERROR: falta RENTAS_DEMO_USERS_PASSWORD en .env." >&2; exit 1; }

TOKEN=$(curl --fail --silent --show-error --header "Content-Type: application/json" \
  --data "{\"username\":\"rentas\",\"password\":\"$PASSWORD\"}" "http://localhost:${BACKEND_PORT:-8080}/api/v1/dev-auth/login" \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || { echo "ERROR: no se pudo ingresar como 'rentas'." >&2; exit 1; }
[ -z "${GITHUB_ACTIONS:-}" ] || echo "::add-mask::$TOKEN"

mkdir -p "$REPORT_DIR" && chmod 777 "$REPORT_DIR"
# dev-auth queda afuera: /logout invalidaría la sesión de la prueba y /login, /bootstrap son del modo DEMO.
docker run --rm --network "${DOCKER_NETWORK:-host}" --volume "$PWD/$REPORT_DIR:/reports" \
  schemathesis/schemathesis:4.33.0 run "$API_URL/v3/api-docs" --url "$API_URL" \
  --header "X-Demo-Session: $TOKEN" --exclude-path-regex 'dev-auth' \
  --checks not_a_server_error,response_schema_conformance,status_code_conformance,content_type_conformance \
  --mode all --phases examples,coverage,fuzzing --max-examples 5 --seed 1 --no-color \
  --report junit --report-dir /reports
