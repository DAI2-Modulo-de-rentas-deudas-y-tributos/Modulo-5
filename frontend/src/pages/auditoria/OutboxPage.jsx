import { useState } from "react";
import { useLocation } from "react-router-dom";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import Modal from "../../components/common/Modal.jsx";
import Button from "../../components/common/Button.jsx";
import FormField from "../../components/ui/FormField.jsx";
import useResource from "../../hooks/useResource.js";
import { outboxService } from "../../services/domains/integrations.js";
import { formatDateTime } from "../../lib/format.js";

const STATES = { PENDING: "Pendiente", PUBLISHED: "Enviado", FAILED: "Fallido", DEAD_LETTER: "En cola de errores" };
export default function OutboxPage() {
  const { pathname } = useLocation();
  const homePath = pathname.startsWith("/rentas") ? "/rentas" : "/auditor";
  const [status, setStatus] = useState("");
  const [selected, setSelected] = useState(null);
  const { data, loading, error, reload } = useResource(outboxService.list, []);
  const rows = (data ?? []).filter((row) => !status || row.status === status);
  return <ModuleShell label="Integraciones" title="Eventos" highlight="salientes" description="Seguimiento de los envíos de Rentas a otros módulos."
    homePath={homePath} breadcrumb={[{ id: "salientes", label: "Eventos salientes" }]}>
    <Card title="Bandeja de envíos" description="El reenvío manual todavía no está habilitado. Los reintentos disponibles los gestiona el sistema."
      actions={<Button variant="secondary" loading={loading} onClick={reload}>Actualizar</Button>}>
      <div className="max-w-sm p-5"><FormField label="Estado del envío" type="select" name="outboxStatus" value={status} onChange={(event) => setStatus(event.target.value)}
        options={Object.entries(STATES).map(([value, label]) => ({ value, label }))} /></div>
      <DataTable rows={rows} loading={loading} error={error} onRetry={reload} rowKey={(row) => row.id} emptyTitle="Sin eventos salientes" onRowClick={setSelected} columns={[
        { key: "eventType", header: "Evento" }, { key: "targetModule", header: "Destino" },
        { key: "status", header: "Estado", render: (row) => STATES[row.status] ?? row.status },
        { key: "retryCount", header: "Reintentos" },
        { key: "createdAt", header: "Creado", render: (row) => formatDateTime(row.createdAt) },
        { key: "publishedAt", header: "Enviado", render: (row) => formatDateTime(row.publishedAt) },
      ]} />
    </Card>
    <Modal open={Boolean(selected)} title="Detalle del evento saliente" onClose={() => setSelected(null)}>
      {selected && <>
        <p className="break-all text-sm">{selected.eventType} · {selected.id}</p>
        <p className="text-sm">{STATES[selected.status] ?? selected.status} · Destino {selected.targetModule}</p>
        {selected.errorMessage && <p className="whitespace-pre-wrap text-sm text-red-700">{selected.errorMessage}</p>}
        <pre className="max-h-80 overflow-auto whitespace-pre-wrap break-all rounded bg-neutral-50 p-3 text-xs">{typeof selected.payload === "string" ? selected.payload : JSON.stringify(selected.payload, null, 2)}</pre>
      </>}
    </Modal>
  </ModuleShell>;
}
