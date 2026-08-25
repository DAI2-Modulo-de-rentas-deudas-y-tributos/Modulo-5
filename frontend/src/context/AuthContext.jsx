import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { authService } from "../services/rentasService.js";

const AuthContext = createContext(null);

const SESSION_KEY = "rentas.user";
const TOKEN_KEY = "rentas.token";

function readStoredUser() {
  try {
    const raw = sessionStorage.getItem(SESSION_KEY);
    return raw ? JSON.parse(raw) : null;
  } catch {
    return null;
  }
}

export function AuthProvider({ children }) {
  const [user, setUser] = useState(readStoredUser);
  const [initializing, setInitializing] = useState(true);

  useEffect(() => {
    setInitializing(false);
  }, []);

  const login = useCallback(async (credentials) => {
    const { token, user: profile } = await authService.login(credentials);
    sessionStorage.setItem(TOKEN_KEY, token);
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(profile));
    setUser(profile);
    return profile;
  }, []);

  const logout = useCallback(async () => {
    await authService.logout();
    sessionStorage.removeItem(TOKEN_KEY);
    sessionStorage.removeItem(SESSION_KEY);
    setUser(null);
  }, []);

  const value = useMemo(
    () => ({
      user,
      initializing,
      login,
      logout,
      isAuthenticated: Boolean(user),
      hasRole: (...roles) => Boolean(user) && roles.includes(user.role),
      isSupervisor: user?.role === "SUPERVISOR",
    }),
    [user, initializing, login, logout],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}

export function useAuth() {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth debe usarse dentro de <AuthProvider>.");
  }
  return context;
}
