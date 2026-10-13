#!/usr/bin/env bash
# Enciende la base o apaga el runtime (ECS + RDS) de un ambiente AWS.
# Uso: bash scripts/aws/runtime.sh db-start|stop
# Requiere ECS_CLUSTER, ECS_SERVICE, RDS_INSTANCE y credenciales AWS activas.
set -euo pipefail

: "${ECS_CLUSTER:?Falta ECS_CLUSTER}"
: "${ECS_SERVICE:?Falta ECS_SERVICE}"
: "${RDS_INSTANCE:?Falta RDS_INSTANCE}"

rds_status() {
  aws rds describe-db-instances \
    --db-instance-identifier "${RDS_INSTANCE}" \
    --query 'DBInstances[0].DBInstanceStatus' \
    --output text
}

# El CLI no ofrece un waiter db-instance-stopped.
wait_rds_stopped() {
  for _ in {1..60}; do
    [[ "$(rds_status)" == "stopped" ]] && return 0
    sleep 30
  done
  echo "RDS ${RDS_INSTANCE} no termino de detenerse."
  return 1
}

# RDS solo acepta stop desde available; starting, backing-up o modifying se esperan.
# El estado se asigna antes del case: si describe falla, se corta en vez de esperar 30 min.
stop_rds() {
  local status
  status=$(rds_status) || return 1
  case "${status}" in
    stopping | stopped) return 0 ;;
    available) ;;
    *) aws rds wait db-instance-available --db-instance-identifier "${RDS_INSTANCE}" || return 1 ;;
  esac
  aws rds stop-db-instance --db-instance-identifier "${RDS_INSTANCE}" > /dev/null
}

case "${1:-}" in
  db-start)
    status=$(rds_status)
    case "${status}" in
      stopped)
        aws rds start-db-instance --db-instance-identifier "${RDS_INSTANCE}" > /dev/null
        ;;
      stopping)
        wait_rds_stopped
        aws rds start-db-instance --db-instance-identifier "${RDS_INSTANCE}" > /dev/null
        ;;
    esac
    aws rds wait db-instance-available --db-instance-identifier "${RDS_INSTANCE}"
    ;;
  stop)
    # Cada paso se intenta aunque falle el anterior: RDS es el costo que no debe quedar encendido.
    failed=0
    aws ecs update-service \
      --cluster "${ECS_CLUSTER}" \
      --service "${ECS_SERVICE}" \
      --desired-count 0 \
      > /dev/null || failed=1
    aws ecs wait services-stable \
      --cluster "${ECS_CLUSTER}" \
      --services "${ECS_SERVICE}" || failed=1
    stop_rds || failed=1
    exit "${failed}"
    ;;
  *)
    echo "Uso: $0 db-start|stop"
    exit 2
    ;;
esac
