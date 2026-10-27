/** Catálogos compartidos por vistas, aislados por sesión y sin cachear errores. */
export function createSessionLookup(loader) {
  let cached = null;
  return {
    load() {
      const token = sessionStorage.getItem("rentas.token");
      if (cached?.token === token) return cached.promise;
      const record = { token, promise: null };
      record.promise = Promise.resolve().then(loader).catch((error) => {
        if (cached === record) cached = null;
        throw error;
      });
      cached = record;
      return record.promise;
    },
    clear() { cached = null; },
  };
}
