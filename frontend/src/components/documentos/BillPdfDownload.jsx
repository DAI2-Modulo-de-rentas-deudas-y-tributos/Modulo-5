import { useState } from "react";
import { Download } from "lucide-react";
import { request, USE_MOCKS } from "../../services/apiClient.js";

/** Descarga autenticada, también cuando la API está alojada en otro dominio. */
export default function BillPdfDownload({ billId }) {
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState(null);

  async function download() {
    setLoading(true);
    setError(null);
    try {
      const { blob, filename } = await request(`/api/v1/bills/${billId}/document`, { responseType: "blob" });
      const url = URL.createObjectURL(blob);
      const link = document.createElement("a");
      link.href = url;
      link.download = filename;
      document.body.append(link);
      try {
        link.click();
      } finally {
        link.remove();
        // Dar tiempo al navegador para iniciar la descarga antes de liberar el blob.
        setTimeout(() => URL.revokeObjectURL(url), 1000);
      }
    } catch (failure) {
      setError(failure.message || "No se pudo descargar la boleta. Intentá nuevamente.");
    } finally {
      setLoading(false);
    }
  }

  return (
    <span className="inline-flex flex-col items-end gap-1">
      <button type="button" onClick={download} disabled={loading || USE_MOCKS}
        title={USE_MOCKS ? "La descarga PDF requiere conexión con el backend." : "Descargar boleta PDF"}
        className="inline-flex items-center gap-1.5 text-[12px] font-semibold text-[#0F2C59] transition-colors hover:text-[#D63031] disabled:opacity-50">
        <Download className="h-3.5 w-3.5" strokeWidth={2} />
        {loading ? "Descargando…" : "PDF"}
      </button>
      {error && <span role="alert" className="text-[12px] text-red-700">{error}</span>}
    </span>
  );
}
