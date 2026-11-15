#!/usr/bin/env bash
# Ejecuta el paso real de ambos workflows con AWS y las esperas simulados.
set -euo pipefail
cd "$(dirname "$0")/../.."

test_dir=$(mktemp -d)
trap 'rm -f -- "${test_dir}"/{calls,sleeps,deploy.sh,output}; rmdir -- "${test_dir}"' EXIT
export ECS_CLUSTER=test-cluster ECS_SERVICE=test-service TASK_DEFINITION_ARN=backend:35
export MOCK_COUNTER="${test_dir}/calls" MOCK_SLEEPS="${test_dir}/sleeps"

aws() {
  case "$1 $2" in
    'ecs update-service') return 0 ;;
    'ecs wait')
      if [[ "${MOCK_SCENARIO}" == waiter-error ]]; then return 42; fi
      ;;
    'ecs describe-services')
      local calls arn="${TASK_DEFINITION_ARN}" state=COMPLETED
      read -r calls < "${MOCK_COUNTER}"
      calls=$((calls + 1))
      echo "${calls}" > "${MOCK_COUNTER}"
      case "${MOCK_SCENARIO}" in
        delayed) if (( calls < 3 )); then state=IN_PROGRESS; fi ;;
        failed) state=FAILED ;;
        rollback) arn=backend:34 ;;
        timeout) state=IN_PROGRESS ;;
        unknown) state=UNKNOWN ;;
        missing) arn=None; state=None ;;
        api-error) echo 'AWS API error' >&2; return 42 ;;
      esac
      printf '%s\t%s\n' "${arn}" "${state}"
      ;;
    *) echo "Llamada AWS inesperada: $*" >&2; return 99 ;;
  esac
}

sleep() {
  [[ "$1" == 10 ]]
  local count
  read -r count < "${MOCK_SLEEPS}"
  echo "$((count + 1))" > "${MOCK_SLEEPS}"
}
export -f aws sleep

check() {
  local scenario=$1 expected_status=$2 expected_calls=$3 expected_sleeps=$4
  local status=0 calls sleeps
  echo 0 > "${MOCK_COUNTER}"
  echo 0 > "${MOCK_SLEEPS}"
  MOCK_SCENARIO="${scenario}" bash "${test_dir}/deploy.sh" > "${test_dir}/output" 2>&1 || status=$?
  read -r calls < "${MOCK_COUNTER}"
  read -r sleeps < "${MOCK_SLEEPS}"
  if [[ "${status}" != "${expected_status}" || "${calls}" != "${expected_calls}" || "${sleeps}" != "${expected_sleeps}" ]]; then
    cat "${test_dir}/output"
    echo "FAIL ${workflow}: ${scenario}; status=${status}, calls=${calls}, sleeps=${sleeps}" >&2
    exit 1
  fi
  echo "PASS ${workflow}: ${scenario}"
}

for workflow in .github/workflows/cd-backend.yml .github/workflows/cd-backend-test.yml; do
  awk '
    /^      - name: Deploy service and wait until stable$/ { step = 1; next }
    step && /^      - name:/ { exit }
    step && /^        run: \|$/ { body = 1; next }
    body { sub(/^          /, ""); print }
  ' "${workflow}" > "${test_dir}/deploy.sh"
  [[ -s "${test_dir}/deploy.sh" ]]
  bash -n "${test_dir}/deploy.sh"
  check completed 0 1 0
  check delayed 0 3 2
  check failed 1 1 0
  check rollback 1 1 0
  check timeout 1 60 59
  check unknown 1 1 0
  check missing 1 1 0
  check api-error 42 1 0
  check waiter-error 42 0 0
done
