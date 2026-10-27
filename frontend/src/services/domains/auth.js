import { AUTH_MODE, ApiError, request } from "../apiClient.js";

export const UI_ROLES = { RENTAS: "PERSONAL", SUPERVISOR: "SUPERVISOR", CASHIER: "CAJERO", AUDITOR: "AUDITOR", TAXPAYER: "CONTRIBUYENTE" };

export const ROLE_LABELS = { RENTAS: "Personal de Rentas", SUPERVISOR: "Supervisor de Rentas", CASHIER: "Cajero de Rentas", AUDITOR: "Auditor de Rentas", TAXPAYER: "Contribuyente" };

export const mapDemoAuthSession = (result) => ({
  token: result.token,
  user: {
    ...result.user,
    fullName: result.user.displayName,
    roleLabel: ROLE_LABELS[result.user.role],
    email: result.user.username,
    backendRole: result.user.role,
    devAuthorities: result.user.authorities,
    role: UI_ROLES[result.user.role],
  },
});

export const authService = {
  async login({ username, password }) {
    if (AUTH_MODE === "core") {
      throw new ApiError("La autenticación Core/JWT todavía no tiene un contrato integrado.", 503, null, "CORE_AUTH_PENDING");
    }
    const result = await request("/api/v1/dev-auth/login", { method: "POST", body: { username, password }, requiresAuth: false });
    return mapDemoAuthSession(result);
  },

  async me() {
    if (AUTH_MODE === "core") {
      throw new ApiError("La autenticación Core/JWT todavía no tiene un contrato integrado.", 503, null, "CORE_AUTH_PENDING");
    }
    const user = await request("/api/v1/dev-auth/me");
    return mapDemoAuthSession({ token: sessionStorage.getItem("rentas.token"), user }).user;
  },

  async logout(token = sessionStorage.getItem("rentas.token")) {
    if (AUTH_MODE === "core") return;
    await request("/api/v1/dev-auth/logout", {
      method: "POST",
      requiresAuth: false,
      ...(token ? { headers: { "X-Demo-Session": token } } : {}),
    });
  },
};
