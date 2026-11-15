/**
 * Usuarios DEMO sólo para la suite de Vitest. No se importan desde el runtime:
 * el login de la app siempre llama a POST /api/v1/dev-auth/login.
 */
export const DEMO_AUTH_FIXTURES = [
  {
    password: "rentas123",
    user: {
      id: 1,
      username: "mrivas",
      displayName: "Mariana Rivas",
      role: "RENTAS",
      authorities: ["RENTAS"],
      taxpayerId: null,
      active: true,
    },
  },
  {
    password: "rentas123",
    user: {
      id: 2,
      username: "jlopez",
      displayName: "Julián López",
      role: "SUPERVISOR",
      authorities: ["RENTAS", "SUPERVISOR"],
      taxpayerId: null,
      active: true,
    },
  },
  {
    password: "caja123",
    user: {
      id: 3,
      username: "pcabrera",
      displayName: "Paula Cabrera",
      role: "CASHIER",
      authorities: ["CASHIER"],
      taxpayerId: null,
      active: true,
    },
  },
  {
    password: "audit123",
    user: {
      id: 4,
      username: "acastro",
      displayName: "Ana Castro",
      role: "AUDITOR",
      authorities: ["AUDITOR"],
      taxpayerId: null,
      active: true,
    },
  },
  {
    password: "ciudadano123",
    user: {
      id: 5,
      username: "jperez",
      displayName: "Juan Pérez",
      role: "TAXPAYER",
      authorities: ["TAXPAYER"],
      taxpayerId: 123,
      active: true,
    },
  },
];
