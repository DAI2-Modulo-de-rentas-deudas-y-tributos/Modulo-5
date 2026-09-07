import { createContext, useCallback, useContext, useEffect, useMemo, useState } from "react";
import { authService } from "../services/rentasService.js";
import { AUTH_MODE } from "../services/apiClient.js";

const AuthContext = createContext(null);

const SESSION_KEY = "rentas.user";
const TOKEN_KEY = "rentas.token";

function readStoredUser() {
  // Una sesión demo previa nunca autentica una futura ejecución en modo Core.
  if (AUTH_MODE === "core") return null;
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

  /**
   * Autentica y, si el llamador lo acepta, abre la sesión.
   *
   * `accept` existe por la pantalla de ingreso, que tiene una puerta por área: unas
   * credenciales pueden ser válidas y aun así no corresponder a la puerta elegida.
   * En ese caso el perfil vuelve con `accepted: false` y la sesión no se abre, para
   * que el login pueda avisar y ofrecer la puerta correcta sin dejar al usuario
   * adentro de un área que no es la suya.
   */
  const login = useCallback(async (credentials, { accept } = {}) => {
    const { token, user: profile } = await authService.login(credentials);
    if (accept && !accept(profile)) return { profile, accepted: false };

    sessionStorage.setItem(TOKEN_KEY, token);
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(profile));
    setUser(profile);
    return { profile, accepted: true };
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
