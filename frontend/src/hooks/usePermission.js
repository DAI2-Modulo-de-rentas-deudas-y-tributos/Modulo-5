import { useCallback } from "react";
import { useOptionalAuth } from "../context/AuthContext.jsx";
import { canPerform, hasAuthority } from "../config/permissions.js";

export default function usePermission() {
  const { user } = useOptionalAuth() ?? {};
  return {
    can: useCallback((permission) => canPerform(user, permission), [user]),
    hasAuthority: useCallback((authority) => hasAuthority(user, authority), [user]),
  };
}
