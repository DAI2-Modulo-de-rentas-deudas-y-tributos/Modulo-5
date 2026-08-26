/**
 * Áreas de trabajo por rol. Cada rol entra a su propio espacio y no ve el del otro:
 * Personal y Supervisor trabajan en `/rentas`, el Cajero en `/caja`, el Auditor en
 * `/auditor` —de sólo lectura— y el Contribuyente en `/portal`, que es el único
 * espacio de cara al ciudadano y no al agente municipal.
 */
export const WORKSPACES = {
  PERSONAL: { home: "/rentas", label: "Personal de Rentas" },
  SUPERVISOR: { home: "/rentas", label: "Supervisión de Rentas" },
  CAJERO: { home: "/caja", label: "Ventanilla de Caja" },
  AUDITOR: { home: "/auditor", label: "Auditoría de Rentas" },
  CONTRIBUYENTE: { home: "/portal", label: "Portal del Contribuyente" },
};

export const homePathForRole = (role) => WORKSPACES[role]?.home ?? "/rentas";
