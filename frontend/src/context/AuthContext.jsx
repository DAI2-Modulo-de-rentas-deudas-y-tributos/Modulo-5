import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { authService } from "../services/rentasService.js";
import { ApiError, AUTH_MODE } from "../services/apiClient.js";

const AuthContext = createContext(null);

const SESSION_KEY = "rentas.user";
const TOKEN_KEY = "rentas.token";

function hasStoredToken() {
  if (AUTH_MODE === "core") return false;
  return Boolean(sessionStorage.getItem(TOKEN_KEY));
}

export function AuthProvider({ children }) {
  const sessionGeneration = useRef(0);
  const [user, setUser] = useState(null);
  const [initializing, setInitializing] = useState(hasStoredToken);

  useEffect(() => {
    let cancelled = false;
    const generation = sessionGeneration.current;
    async function restore() {
      if (AUTH_MODE === "core") {
        setInitializing(false);
        return;
      }
      const token = sessionStorage.getItem(TOKEN_KEY);
      if (!token) {
        setUser(null);
        setInitializing(false);
        return;
      }
      try {
        const profile = await authService.me();
        if (cancelled || generation !== sessionGeneration.current) return;
        sessionStorage.setItem(SESSION_KEY, JSON.stringify(profile));
        setUser(profile);
      } catch {
        if (cancelled || generation !== sessionGeneration.current) return;
        sessionStorage.removeItem(TOKEN_KEY);
        sessionStorage.removeItem(SESSION_KEY);
        setUser(null);
      } finally {
        if (!cancelled && generation === sessionGeneration.current) setInitializing(false);
      }
    }
    restore();
    return () => {
      cancelled = true;
    };
  }, []);

  /** Autentica y abre la sesión únicamente si el perfil corresponde al acceso elegido. */
  const login = useCallback(async (credentials, { accept } = {}) => {
    const generation = ++sessionGeneration.current;
    const { token, user: profile } = await authService.login(credentials);
    if (generation !== sessionGeneration.current || (accept && !accept(profile))) {
      // El perfil nunca se expone al login si el usuario eligió otro acceso.
      // La revocación es best-effort: aunque falle, el token no se guarda localmente.
      try {
        await authService.logout(token);
      } catch {
        // La respuesta visible debe ser indistinguible de unas credenciales inválidas.
      }
      throw new ApiError("Usuario o contraseña incorrectos.", 401, null, "INVALID_CREDENTIALS");
    }

    sessionStorage.setItem(TOKEN_KEY, token);
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(profile));
    setUser(profile);
    setInitializing(false);
    return { profile, accepted: true };
  }, []);

  const logout = useCallback(async () => {
    ++sessionGeneration.current;
    const token = sessionStorage.getItem(TOKEN_KEY);
    // Cerrar localmente antes de esperar la red evita restauraciones tardías.
    sessionStorage.removeItem(TOKEN_KEY);
    sessionStorage.removeItem(SESSION_KEY);
    setUser(null);
    setInitializing(false);
    if (token) await authService.logout(token);
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
