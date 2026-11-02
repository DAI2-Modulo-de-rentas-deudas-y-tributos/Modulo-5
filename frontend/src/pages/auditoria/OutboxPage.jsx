import { useCallback, useMemo, useState } from "react";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import FilterBar from "../../components/common/FilterBar.jsx";
import Button from "../../components/common/Button.jsx";
import Modal from "../../components/common/Modal.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Alert from "../../components/ui/Alert.jsx";
import useResource from "../../hooks/useResource.js";
import { integrationOutboxService } from "../../services/integrationOutboxService.js";
import { formatDateTime } from "../../lib/format.js";
import { useAuth } from "../../context/AuthContext.jsx";
import { homePathForRole } from "../../config/workspaces.js";

const PAGE_SIZE = 20;
const STATES = {
  PENDING: "Pendiente", LOCAL_ONLY: "Solo local", BROKER_CONFIRMED: "Confirmado por broker",
  CORE_ACCEPTED: "Aceptado por Core", PUBLISHED: "Publicado", FAILED: "Fallido", DEAD_LETTER: "Reintentos agotados",
};
const badge = (status) => <StatusBadge status={status} label={STATES[status] ?? status}
  tone={["FAILED", "DEAD_LETTER"].includes(status) ? "danger" : undefined} />;
function readablePayload(payload) {
  if (payload == null) return "Sin contenido informado.";
  try { return JSON.stringify(typeof payload === "string" ? JSON.parse(payload) : payload, null, 2); }
  catch { return String(payload); }
}

export default function OutboxPage() {
  const { user } = useAuth();
  const loader = useCallback(() => integrationOutboxService.list(), []);
  const { data, loading, error, reload } = useResource(loader, []);
  const [filters, setFilters] = useState({ status: "", query: "" });
  const [page, setPage] = useState(0);
  const [selected, setSelected] = useState(null);
  const filtered = useMemo(() => {
    const query = filters.query.trim().toLocaleLowerCase("es");
    return (data ?? []).filter((row) => (!filters.status || row.status === filters.status)
      && (!query || [row.id, row.eventType, row.targetModule, row.aggregateId].some((value) => String(value ?? "").toLocaleLowerCase("es").includes(query))));
  }, [data, filters]);
  const pages = Math.ceil(filtered.length / PAGE_SIZE);
  const currentPage = Math.min(page, Math.max(0, pages - 1));
  const changeFilter = (name, value) => { setFilters((previous) => ({ ...previous, [name]: value })); setPage(0); };
  const columns = [
    { key: "eventType", header: "Evento" },
    { key: "targetModule", header: "Destino" },
    { key: "aggregateId", header: "Referencia" },
    { key: "status", header: "Estado", render: (row) => badge(row.status) },
    { key: "retryCount", header: "Reintentos", align: "right" },
    { key: "createdAt", header: "Creado", render: (row) => formatDateTime(row.createdAt) },
    { key: "detail", header: "", render: (row) => <Button size="sm" variant="secondary" onClick={() => setSelected(row)}>Ver detalle</Button> },
  ];
  return <ModuleShell label="Integraciones" title="Eventos salientes" description="Estado de los envíos a otros módulos y resultado de cada intento."
    homePath={homePathForRole(user.role)}
    breadcrumb={[{ id: "outbox", label: "Eventos salientes" }]}>
    <Alert variant="info" title="Reenvío manual no disponible">Los reintentos automáticos los gestiona el sistema. El estado «Solo local» no confirma recepción en otro módulo.</Alert>
    <Card title="Bandeja de envíos" actions={<Button variant="secondary" loading={loading} onClick={reload}>Actualizar</Button>}>
      <FilterBar searchValue={filters.query} searchPlaceholder="Evento, destino o referencia…" onSearchChange={(value) => changeFilter("query", value)}
        filters={[{ name: "status", label: "Estado", options: Object.entries(STATES).map(([value, label]) => ({ value, label })) }]}
        values={filters} onFilterChange={changeFilter} />
      {error ? <Alert variant="error" title="No se pudieron cargar los eventos"><p>{error}</p><Button variant="secondary" onClick={reload}>Reintentar</Button></Alert>
        : <DataTable columns={columns} rows={filtered.slice(currentPage * PAGE_SIZE, (currentPage + 1) * PAGE_SIZE)} rowKey={(row) => row.id} loading={loading}
          emptyTitle="Sin eventos salientes" emptyDescription="No hay eventos que coincidan con los filtros." emptyIconName="Send" />}
      {!loading && !error && filtered.length > 0 && <nav aria-label="Paginación de eventos" className="flex flex-wrap items-center justify-between gap-3 p-4 text-sm">
        <span>{filtered.length} eventos · Página {currentPage + 1} de {pages}</span>
        <div className="flex gap-2"><Button variant="secondary" disabled={currentPage === 0} onClick={() => setPage(currentPage - 1)}>Anterior</Button>
          <Button variant="secondary" disabled={currentPage + 1 >= pages} onClick={() => setPage(currentPage + 1)}>Siguiente</Button></div>
      </nav>}
    </Card>
    {selected && <Modal open title="Detalle del evento saliente" description={selected.id} onClose={() => setSelected(null)} footer={<Button variant="secondary" onClick={() => setSelected(null)}>Cerrar</Button>}>
      <div className="flex flex-col gap-4">
        {badge(selected.status)}
        <dl className="grid grid-cols-2 gap-3 text-sm">
          <div><dt className="text-neutral-500">Evento</dt><dd>{selected.eventType}</dd></div>
          <div><dt className="text-neutral-500">Destino</dt><dd>{selected.targetModule}</dd></div>
          <div><dt className="text-neutral-500">Creado</dt><dd>{formatDateTime(selected.createdAt)}</dd></div>
          <div><dt className="text-neutral-500">Publicado</dt><dd>{formatDateTime(selected.publishedAt)}</dd></div>
          <div><dt className="text-neutral-500">Último intento</dt><dd>{formatDateTime(selected.lastAttemptAt)}</dd></div>
          <div><dt className="text-neutral-500">Reintentos</dt><dd>{selected.retryCount}</dd></div>
        </dl>
        {selected.errorMessage && <Alert variant="error" title="Último error">{selected.errorMessage}</Alert>}
        <pre className="max-h-80 overflow-auto whitespace-pre-wrap break-words rounded-lg bg-neutral-50 p-4 text-xs">{readablePayload(selected.payload)}</pre>
      </div>
    </Modal>}
  </ModuleShell>;
}
