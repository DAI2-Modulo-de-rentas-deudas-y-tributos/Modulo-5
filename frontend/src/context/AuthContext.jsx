import { createContext, useCallback, useContext, useEffect, useMemo, useRef, useState } from "react";
import { authService } from "../services/rentasService.js";
import { ApiError, AUTH_MODE, SESSION_SOURCE_KEY } from "../services/apiClient.js";
import { onSessionExpired } from "../services/sessionEvents.js";

const AuthContext = createContext(null);

const SESSION_KEY = "rentas.user";
const TOKEN_KEY = "rentas.token";
const PAYMENT_INTENT_KEY = "rentas.electronicPaymentIntent";

function hasStoredToken() {
  if (!sessionStorage.getItem(TOKEN_KEY)) return false;
  return AUTH_MODE === "demo" || ["m1", "staff"].includes(sessionStorage.getItem(SESSION_SOURCE_KEY));
}

/** Quita la sesión guardada. El intento de pago electrónico se conserva sólo al vencer la sesión. */
function clearStoredSession() {
  sessionStorage.removeItem(TOKEN_KEY);
  sessionStorage.removeItem(SESSION_KEY);
  sessionStorage.removeItem(SESSION_SOURCE_KEY);
}

export function AuthProvider({ children }) {
  const sessionGeneration = useRef(0);
  const [user, setUser] = useState(null);
  const [initializing, setInitializing] = useState(hasStoredToken);
  const [sessionNotice, setSessionNotice] = useState(null);

  useEffect(() => onSessionExpired((token) => {
    if (sessionStorage.getItem(TOKEN_KEY) !== token) return;
    ++sessionGeneration.current;
    clearStoredSession();
    setUser(null);
    setInitializing(false);
    setSessionNotice("Tu sesión venció. Ingresá nuevamente para continuar.");
  }), []);

  const dismissSessionNotice = useCallback(() => setSessionNotice(null), []);

  useEffect(() => {
    let cancelled = false;
    const generation = sessionGeneration.current;
    async function restore() {
      if (!hasStoredToken()) {
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
        clearStoredSession();
        sessionStorage.removeItem(PAYMENT_INTENT_KEY);
        setUser(null);
      } finally {
        if (!cancelled && generation === sessionGeneration.current) setInitializing(false);
      }
    }
    void restore();
    return () => {
      cancelled = true;
    };
  }, []);

  /**
   * Autentica y abre la sesión únicamente si el perfil corresponde al acceso elegido.
   * `source: "m1"` ingresa con la cuenta de Ciudadanos; por defecto, con la cuenta DEMO de Rentas.
   */
  const login = useCallback(async (credentials, { accept, source = "demo", onRetry } = {}) => {
    const generation = ++sessionGeneration.current;
    const { token, user: profile } = source === "m1"
      ? await authService.loginM1(credentials, { onRetry })
      : await authService.login(credentials);
    if (generation !== sessionGeneration.current || (accept && !accept(profile))) {
      // El perfil nunca se expone al login si el usuario eligió otro acceso.
      // La revocación es best-effort: aunque falle, el token no se guarda localmente.
      try {
        await authService.logout(token, source);
      } catch {
        // La respuesta visible debe ser indistinguible de unas credenciales inválidas.
      }
      throw new ApiError("Usuario o contraseña incorrectos.", 401, null, "INVALID_CREDENTIALS");
    }

    sessionStorage.setItem(TOKEN_KEY, token);
    sessionStorage.setItem(SESSION_KEY, JSON.stringify(profile));
    sessionStorage.setItem(SESSION_SOURCE_KEY, source);
    setUser(profile);
    setInitializing(false);
    setSessionNotice(null);
    return { profile, accepted: true };
  }, []);

  const logout = useCallback(async () => {
    ++sessionGeneration.current;
    const token = sessionStorage.getItem(TOKEN_KEY);
    const source = sessionStorage.getItem(SESSION_SOURCE_KEY);
    // Cerrar localmente antes de esperar la red evita restauraciones tardías.
    clearStoredSession();
    sessionStorage.removeItem(PAYMENT_INTENT_KEY);
    setUser(null);
    setInitializing(false);
    setSessionNotice(null);
    if (token) {
      try {
        await authService.logout(token, source);
      } catch {
        setSessionNotice("Cerraste la sesión en este dispositivo, pero no se pudo confirmar la revocación en el servidor.");
      }
    }
  }, []);

  const value = useMemo(
    () => ({
      user,
      initializing,
      sessionNotice,
      dismissSessionNotice,
      login,
      logout,
      isAuthenticated: Boolean(user),
      hasRole: (...roles) => Boolean(user) && roles.includes(user.role),
      isSupervisor: user?.role === "SUPERVISOR",
    }),
    [user, initializing, sessionNotice, dismissSessionNotice, login, logout],
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
