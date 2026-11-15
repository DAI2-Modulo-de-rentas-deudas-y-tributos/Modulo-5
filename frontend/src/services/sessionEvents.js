const expirationListeners = new Set();

export function onSessionExpired(listener) {
  expirationListeners.add(listener);
  return () => expirationListeners.delete(listener);
}

export function notifySessionExpired(token) {
  for (const listener of expirationListeners) listener(token);
}
