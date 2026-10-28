/** Sólo enlaces web absolutos: nunca ejecutar URLs entregadas por tickets o adjuntos. */
export function safeExternalUrl(value) {
  if (typeof value !== "string" || !value.trim() || /[\u0000-\u0020\u007f]/.test(value)) return null;
  try {
    const url = new URL(value);
    return ["https:", "http:"].includes(url.protocol) && !url.username && !url.password ? url.href : null;
  } catch { return null; }
}
