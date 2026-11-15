// Prueba de carga (Eje C): 50 usuarios concurrentes durante 5 minutos sobre los 3 endpoints
// críticos del portal del contribuyente. Falla si alguno supera p95 >= 500 ms o si los errores
// llegan al 1 %. Corre en .github/workflows/no-funcionales.yml contra el stack de Compose.
//   docker run --rm --network host -e DEMO_PASSWORD=... -v "$PWD/qa/carga:/scripts" grafana/k6 run /scripts/endpoints-criticos.js
import http from "k6/http";
import { check, fail, sleep } from "k6";

const API = __ENV.API_URL || "http://localhost:8080/api/v1";

export const options = {
  scenarios: {
    contribuyentes: { executor: "constant-vus", vus: Number(__ENV.VUS || 50), duration: __ENV.DURATION || "5m" },
  },
  thresholds: {
    "http_req_duration{endpoint:deudas}": ["p(95)<500"],
    "http_req_duration{endpoint:boletas}": ["p(95)<500"],
    "http_req_duration{endpoint:simulacion-plan}": ["p(95)<500"],
    "http_req_failed{endpoint:deudas}": ["rate<0.01"],
    "http_req_failed{endpoint:boletas}": ["rate<0.01"],
    "http_req_failed{endpoint:simulacion-plan}": ["rate<0.01"],
  },
};

function json(response) {
  if (response.status !== 200) fail(`${response.request.method} ${response.url} respondió ${response.status}`);
  return response.json();
}

export function setup() {
  if (!__ENV.DEMO_PASSWORD) fail("Falta DEMO_PASSWORD (RENTAS_DEMO_USERS_PASSWORD del .env).");
  // Una sola sesión: el login tiene límite de intentos (429) y no es uno de los endpoints medidos.
  const login = json(http.post(`${API}/dev-auth/login`,
    JSON.stringify({ username: "contribuyente", password: __ENV.DEMO_PASSWORD }),
    { headers: { "Content-Type": "application/json" } }));
  const headers = { "X-Demo-Session": login.token, "Content-Type": "application/json" };
  const me = json(http.get(`${API}/dev-auth/me`, { headers }));
  const debts = json(http.get(`${API}/taxpayers/${me.taxpayerId}/debts`, { headers })).content
    .filter((debt) => debt.outstandingBalance > 0 && !debt.inPaymentPlan);
  if (debts.length === 0) fail("El contribuyente demo no tiene deudas para simular un plan.");
  return { headers, taxpayerId: me.taxpayerId, debtIds: debts.map((debt) => debt.id) };
}

export default function ({ headers, taxpayerId, debtIds }) {
  const tagged = (endpoint) => ({ headers, tags: { endpoint } });

  check(http.get(`${API}/taxpayers/${taxpayerId}/debts`, tagged("deudas")), {
    "deudas: 200": (response) => response.status === 200,
  });
  check(http.get(`${API}/taxpayers/${taxpayerId}/bills`, tagged("boletas")), {
    "boletas: 200": (response) => response.status === 200,
  });
  check(http.post(`${API}/payment-plans/simulations`,
    JSON.stringify({ taxpayerId, debtIds, installments: 6 }), tagged("simulacion-plan")), {
    "simulación de plan: 200": (response) => response.status === 200,
  });
  sleep(1); // cada VU es un usuario: consulta, mira y vuelve a consultar
}
