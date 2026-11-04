#!/bin/sh
# Prueba de carga con k6 (qa/carga/endpoints-criticos.js) contra el sistema levantado con
# `docker compose up -d --build`. Deja el reporte HTML y el resumen JSON en k6-report/.
# Desde la raíz del repo:  sh scripts/compose/load-test.sh
# Lee la contraseña DEMO del .env; nunca la imprime.
set -eu

REPORT_DIR="${REPORT_DIR:-k6-report}"
DEMO_PASSWORD=$(sed -n 's/^RENTAS_DEMO_USERS_PASSWORD=//p' .env | tr -d '\r')
[ -n "$DEMO_PASSWORD" ] || { echo "ERROR: falta RENTAS_DEMO_USERS_PASSWORD en .env." >&2; exit 1; }
export DEMO_PASSWORD

mkdir -p "$REPORT_DIR" && chmod 777 "$REPORT_DIR"
if docker run --rm --network "${DOCKER_NETWORK:-host}" --env DEMO_PASSWORD --env API_URL --env VUS --env DURATION \
  --env K6_WEB_DASHBOARD=true --env K6_WEB_DASHBOARD_EXPORT=/reports/reporte-k6.html \
  --volume "$PWD/qa/carga:/scripts:ro" --volume "$PWD/$REPORT_DIR:/reports" \
  grafana/k6:1.4.2 run --summary-export /reports/resumen-k6.json /scripts/endpoints-criticos.js; then
  status=0
else
  status=$?
fi

SUMMARY="$REPORT_DIR/resumen-k6.json"
if [ -f "$SUMMARY" ]; then
  if ! node -e 'const fs=require("node:fs");const p=process.argv[1];try{const data=JSON.parse(fs.readFileSync(p,"utf8"));if(data.setup_data)delete data.setup_data.headers;const output=JSON.stringify(data,null,2);if(output.includes("X-Demo-Session"))throw new Error("session header remains");fs.writeFileSync(p,output+"\n")}catch{fs.rmSync(p,{force:true});process.exit(1)}' "$SUMMARY"; then
    status=1
  fi
fi

exit "$status"
