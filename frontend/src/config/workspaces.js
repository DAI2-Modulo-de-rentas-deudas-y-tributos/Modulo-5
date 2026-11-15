export const WORKSPACES = {
  PERSONAL: "/rentas",
  SUPERVISOR: "/rentas",
  CAJERO: "/caja",
  AUDITOR: "/auditor",
  CONTRIBUYENTE: "/portal",
};

export const homePathForRole = (role) => WORKSPACES[role] ?? "/rentas";
