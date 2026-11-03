#!/bin/sh
# Escaneo OWASP ZAP (Eje D) sobre el sistema levantado con `docker compose up -d --build`:
#   1. baseline (pasivo) sobre el portal;
#   2. activo y autenticado sobre la API, a partir de su OpenAPI.
# Falla si hay alertas High; las Medium quedan en el reporte para justificarlas una por una.
# Desde la raíz del repo:  sh scripts/compose/zap-scan.sh
# En Docker Desktop: FRONTEND_URL/API_URL con host.docker.internal y DOCKER_NETWORK=bridge
# (y en Git Bash, MSYS_NO_PATHCONV=1). Lee el .env; nunca imprime la contraseña ni la sesión.
set -eu

FRONTEND_URL="${FRONTEND_URL:-http://localhost:${FRONTEND_PORT:-5173}}"
API_URL="${API_URL:-http://localhost:${BACKEND_PORT:-8080}}"
REPORT_DIR="${REPORT_DIR:-zap-report}"
ACTIVE_SCAN_MINUTES="${ACTIVE_SCAN_MINUTES:-20}"
ZAP="ghcr.io/zaproxy/zaproxy:2.17.0"

PASSWORD=$(sed -n 's/^RENTAS_DEMO_USERS_PASSWORD=//p' .env | tr -d '\r')
[ -n "$PASSWORD" ] || { echo "ERROR: falta RENTAS_DEMO_USERS_PASSWORD en .env." >&2; exit 1; }
TOKEN=$(curl --fail --silent --show-error --header "Content-Type: application/json" \
  --data "{\"username\":\"rentas\",\"password\":\"$PASSWORD\"}" "http://localhost:${BACKEND_PORT:-8080}/api/v1/dev-auth/login" \
  | sed -n 's/.*"token":"\([^"]*\)".*/\1/p')
[ -n "$TOKEN" ] || { echo "ERROR: no se pudo ingresar como 'rentas'." >&2; exit 1; }
[ -z "${GITHUB_ACTIONS:-}" ] || echo "::add-mask::$TOKEN"

mkdir -p "$REPORT_DIR" && chmod 777 "$REPORT_DIR"
zap() { docker run --rm --network "${DOCKER_NETWORK:-host}" --volume "$PWD/$REPORT_DIR:/zap/wrk:rw" "$ZAP" "$@"; }

# -I: las advertencias no cortan el escaneo; el criterio (0 High) se evalúa al final.
zap zap-baseline.py -t "$FRONTEND_URL" -J baseline.json -r baseline.html -I

# La sesión DEMO viaja en cada request. dev-auth queda afuera del OpenAPI que recibe ZAP: al
# importarlo llama a cada operación, y /logout invalidaría la sesión del escaneo.
curl --fail --silent --show-error "http://localhost:${BACKEND_PORT:-8080}/v3/api-docs" | node -e '
  let raw = ""; process.stdin.on("data", (chunk) => { raw += chunk; }).on("end", () => {
    const spec = JSON.parse(raw);
    for (const path of Object.keys(spec.paths)) if (path.includes("/dev-auth/")) delete spec.paths[path];
    process.stdout.write(JSON.stringify(spec));
  });' > "$REPORT_DIR/openapi.json"
zap zap-api-scan.py -t /zap/wrk/openapi.json -f openapi -O "$API_URL" -J api.json -r api.html -I -z "\
-config replacer.full_list(0).description=sesion-demo \
-config replacer.full_list(0).enabled=true \
-config replacer.full_list(0).matchtype=REQ_HEADER \
-config replacer.full_list(0).matchstr=X-Demo-Session \
-config replacer.full_list(0).regex=false \
-config replacer.full_list(0).replacement=$TOKEN \
-config globalexcludeurl.url_list.url(0).regex=.*/dev-auth/.* \
-config globalexcludeurl.url_list.url(0).description=sesion-demo \
-config globalexcludeurl.url_list.url(0).enabled=true \
-config scanner.maxScanDurationInMins=$ACTIVE_SCAN_MINUTES"

status=$(curl --silent --output /dev/null --write-out '%{http_code}' --header "X-Demo-Session: $TOKEN" \
  "http://localhost:${BACKEND_PORT:-8080}/api/v1/dev-auth/me")
[ "$status" = 200 ] || { echo "ERROR: la sesión DEMO no sobrevivió al escaneo ($status): el activo no estuvo autenticado." >&2; exit 1; }

high=$(cat "$REPORT_DIR/baseline.json" "$REPORT_DIR/api.json" | grep -c '"riskcode": "3"' || true)
medium=$(cat "$REPORT_DIR/baseline.json" "$REPORT_DIR/api.json" | grep -c '"riskcode": "2"' || true)
echo "ZAP: $high alertas High y $medium Medium (detalle en $REPORT_DIR/)."
[ "$high" = 0 ] || { echo "ERROR: ZAP encontró alertas High." >&2; exit 1; }
