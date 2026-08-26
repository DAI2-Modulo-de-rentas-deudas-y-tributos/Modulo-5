/**
 * Áreas de trabajo por rol. Cada rol entra a su propio espacio y no ve el del otro:
 * Personal y Supervisor trabajan en `/rentas`, el Cajero en `/caja`.
 */
export const WORKSPACES = {
  PERSONAL: { home: "/rentas", label: "Personal de Rentas" },
  SUPERVISOR: { home: "/rentas", label: "Supervisión de Rentas" },
  CAJERO: { home: "/caja", label: "Ventanilla de Caja" },
};

export const homePathForRole = (role) => WORKSPACES[role]?.home ?? "/rentas";
