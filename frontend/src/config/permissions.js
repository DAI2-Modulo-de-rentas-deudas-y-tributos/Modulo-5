const BACKEND_ROLES = { PERSONAL: "RENTAS", SUPERVISOR: "SUPERVISOR", CAJERO: "CASHIER", AUDITOR: "AUDITOR", CONTRIBUYENTE: "TAXPAYER" };

/** Authorities verificadas por login o /me. Una lista vacía del servidor no concede accesos. */
export function authoritiesFor(user) {
  if (!user) return [];
  const authorities = user.devAuthorities ?? user.authorities;
  if (Array.isArray(authorities)) return authorities.filter((value) => typeof value === "string").map((value) => value.replace(/^ROLE_/, ""));
  return [user.backendRole ?? BACKEND_ROLES[user.role]].filter(Boolean);
}

export const hasAuthority = (user, authority) => authoritiesFor(user).includes(authority);
export function hasWorkspaceRole(user, role) {
  if (!user || user.role !== role) return false;
  return hasAuthority(user, BACKEND_ROLES[role]);
}

// Corresponden a @PreAuthorize del contrato actual. El backend vuelve a validar cada operación.
export const PERMISSIONS = {
  "tax.configure": ["RENTAS"],
  "tax.approve": ["SUPERVISOR"],
  "settlement.generate": ["RENTAS"],
  "bill.issue": ["RENTAS"],
  "payment.register": ["RENTAS", "CASHIER"],
  "payment.reversal.request": ["CASHIER"],
  "payment.reversal.approve": ["SUPERVISOR"],
  "payment.reversal.execute": ["RENTAS"],
  "adjustment.request": ["RENTAS"],
  "adjustment.resolve": ["SUPERVISOR"],
  "plan.request": ["TAXPAYER"],
  "ticket.read": ["RENTAS"],
  "plan.resolve": ["RENTAS", "SUPERVISOR"],
  "exemption.resolve": ["SUPERVISOR"],
  "exemption.review": ["RENTAS"],
  "event.reprocess": ["SUPERVISOR", "TECHNICAL"],
  "outbox.read": ["SUPERVISOR", "AUDITOR", "TECHNICAL"],
  "security.read": ["SUPERVISOR", "AUDITOR"],
  "debt.charge": ["RENTAS"],
};

export function canPerform(user, permission) {
  const roles = Object.hasOwn(PERMISSIONS, permission) ? PERMISSIONS[permission] : [];
  return Boolean(roles?.some((role) => hasAuthority(user, role)));
}
