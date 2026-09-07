#!/usr/bin/env bash
# Levanta todo el stack de M5 Rentas para la demo (PostgreSQL real + backend + frontend).
# Uso: ./qa/levantar-demo.sh   (desde cualquier carpeta)

set -euo pipefail
REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$REPO_ROOT"

echo "== 1/4 · PostgreSQL =="
docker compose up -d postgres
for i in $(seq 1 20); do
  docker compose exec -T postgres pg_isready -U rentas -d rentas >/dev/null 2>&1 && break
  sleep 1
done
echo "PostgreSQL listo (localhost:5433)."

echo "== 2/4 · Backend =="
# No alcanza con mirar /actuator/health: otro proceso (ej. otro proyecto Spring Boot
# tuyo) puede estar respondiendo 200 ahí en el mismo puerto 8080. Confirmamos que
# es realmente M5 buscando un path exclusivo suyo en el OpenAPI.
es_m5() {
  curl -s http://localhost:8080/v3/api-docs 2>/dev/null | grep -q '"/api/v1/tax-concepts"'
}
if es_m5; then
  echo "Backend ya estaba arriba (confirmado que es M5)."
else
  if curl -s -o /dev/null -w '%{http_code}' http://localhost:8080/actuator/health 2>/dev/null | grep -q 200; then
    echo "ERROR: el puerto 8080 ya lo tiene ocupado OTRO proceso (no es el backend de M5)."
    echo "Frenalo antes de continuar, por ejemplo: lsof -nP -iTCP:8080 -sTCP:LISTEN"
    exit 1
  fi
  cd "$REPO_ROOT/backend"
  export DB_URL="jdbc:postgresql://localhost:5433/rentas"
  export DB_USER="rentas"
  export DB_PASSWORD="rentas_local"
  export CORS_ALLOWED_ORIGINS="http://localhost:5173,http://localhost:4173"
  export RENTAS_SECURITY_DEV_MODE=true
  export RENTAS_DEMO_BOOTSTRAP_PASSWORD='DemoQA2026!'
  nohup ./mvnw spring-boot:run -Dspring-boot.run.profiles=dev > /tmp/m5-backend.log 2>&1 &
  disown
  echo "Arrancando backend (log: /tmp/m5-backend.log)..."
  for i in $(seq 1 40); do
    es_m5 && break
    sleep 2
  done
  if ! es_m5; then
    echo "ERROR: el backend no respondio a tiempo. Revisar /tmp/m5-backend.log"
    echo "(si dice 'Unable to find a single main class ... candidates', borrar backend/target y reintentar)"
    exit 1
  fi
  cd "$REPO_ROOT"
fi
echo "Backend listo (http://localhost:8080/api/v1 · Swagger en /swagger-ui/index.html)."

echo "== 3/4 · Frontend =="
if curl -s -o /dev/null -w '%{http_code}' http://localhost:5173/ 2>/dev/null | grep -q 200; then
  echo "Frontend ya estaba arriba."
else
  cd "$REPO_ROOT/frontend"
  nohup npm run dev > /tmp/m5-frontend.log 2>&1 &
  disown
  echo "Arrancando frontend (log: /tmp/m5-frontend.log)..."
  sleep 3
  cd "$REPO_ROOT"
fi
echo "Frontend listo (http://localhost:5173)."

echo "== 4/4 · Listo para la demo =="
cat <<'EOF'

Portal:  http://localhost:5173
API:     http://localhost:8080/api/v1
Swagger: http://localhost:8080/swagger-ui/index.html

Usuarios demo (contraseña para todos: DemoQA2026!):
  demo.rentas         -> Personal de Rentas
  demo.supervisor     -> Supervisor
  demo.caja           -> Cajero
  demo.auditoria      -> Auditor
  demo.contribuyente  -> Contribuyente (Portal del Contribuyente)

Insomnia: importar qa/insomnia-m5-rentas-qa.json (baseUrl ya apunta a localhost:8080)
EOF
