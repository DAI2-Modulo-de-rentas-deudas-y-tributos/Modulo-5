const expirationListeners = new Set();

export function onSessionExpired(listener) {
  expirationListeners.add(listener);
  return () => expirationListeners.delete(listener);
}

/** Incluye la sesión usada en la petición para ignorar respuestas de sesiones anteriores. */
export function notifySessionExpired(token) {
  for (const listener of expirationListeners) listener(token);
}
