import { useCallback, useState } from "react";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import FilterBar from "../../components/common/FilterBar.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Modal from "../../components/common/Modal.jsx";
import Button from "../../components/common/Button.jsx";
import Alert from "../../components/ui/Alert.jsx";
import FieldGrid from "../../components/auditoria/FieldGrid.jsx";
import HistoryTimeline from "../../components/auditoria/HistoryTimeline.jsx";
import useResource from "../../hooks/useResource.js";
import { auditService, externalObligationService } from "../../services/rentasService.js";
import { useAuth } from "../../context/AuthContext.jsx";
import { formatCurrency, formatDate, formatDateTime, labelFor } from "../../lib/format.js";

export default function ObligacionesExternasPage() {
  const { user } = useAuth();
  const auditor = user.role === "AUDITOR";
  const [filters, setFilters] = useState({ externalReferenceId: "", status: auditor ? "" : "ERROR", sourceModule: "" });
  const [selected, setSelected] = useState(null);
  const [feedback, setFeedback] = useState(null);
  const loader = useCallback(
    () => auditor ? auditService.externalObligations(filters) : externalObligationService.list(filters),
    [auditor, filters],
  );
  const { data: obligations, loading, error, reload } = useResource(loader, []);

  const columns = [
    { key: "externalReferenceId", header: "Referencia externa", render: (row) => <span className="font-medium tabular-nums">{row.externalReferenceId}</span> },
    { key: "sourceModule", header: "Origen" },
    { key: "externalType", header: "Tipo", render: (row) => labelFor(row.externalType) },
    { key: "externalTaxpayerId", header: "Contribuyente externo" },
    { key: "amount", header: "Importe", align: "right", render: (row) => formatCurrency(row.amount) },
    { key: "receivedAt", header: "Recibida", render: (row) => formatDateTime(row.receivedAt) },
    { key: "retryCount", header: "Reintentos", align: "right" },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
    { key: "actions", header: "", align: "right", render: (row) => <Button size="sm" variant="secondary" onClick={() => setSelected(row)}>Ver traza</Button> },
  ];

  return (
    <ModuleShell
      label={auditor ? "Auditoría" : "Integraciones"}
      title="Obligaciones externas"
      highlight={auditor ? "trazables" : "con errores"}
      description={auditor
        ? "Buscá por referencia externa y reconstruí la recepción, el procesamiento y los reintentos."
        : "Identificá la causa del error y reintentá únicamente cuando el backend lo permita."}
      breadcrumb={[{ id: "obligaciones-externas", label: "Obligaciones externas" }]}
      homePath={auditor ? "/auditor" : "/rentas"}
      homeLabel={auditor ? "Dashboard" : "Inicio"}
    >
      {feedback && <Alert variant="success" title="Reintento solicitado" onDismiss={() => setFeedback(null)}>{feedback}</Alert>}
      {error && <Alert variant="error" title="No pudimos cargar las obligaciones">{error}</Alert>}
      <Card title="Resultados" description="Cada fila conserva la referencia del sistema de origen.">
        <FilterBar
          searchValue={filters.externalReferenceId}
          searchPlaceholder="Referencia externa…"
          onSearchChange={(value) => setFilters((previous) => ({ ...previous, externalReferenceId: value }))}
          filters={[
            { name: "sourceModule", label: "Módulo", options: [{ value: "M4", label: "Comercio (M4)" }, { value: "M7", label: "Tránsito (M7)" }] },
            { name: "status", label: "Estado", options: [{ value: "RECEIVED", label: "Recibida" }, { value: "PROCESSED", label: "Procesada" }, { value: "ERROR", label: "Con error" }] },
          ]}
          values={filters}
          onFilterChange={(name, value) => setFilters((previous) => ({ ...previous, [name]: value }))}
        />
        <DataTable columns={columns} rows={obligations ?? []} rowKey={(row) => row.id} loading={loading} emptyIconName="Webhook" emptyTitle="Sin obligaciones" emptyDescription="No hay resultados que coincidan con los filtros." />
      </Card>
      {selected && (
        <ObligationModal
          obligation={selected}
          auditor={auditor}
          onClose={() => setSelected(null)}
          onRetried={(result) => {
            setSelected(null);
            setFeedback(`La obligación ${result.externalReferenceId} quedó en estado ${result.status}.`);
            reload();
          }}
        />
      )}
    </ModuleShell>
  );
}

function ObligationModal({ obligation, auditor, onClose, onRetried }) {
  const loader = useCallback(
    () => auditor ? auditService.externalObligationDetail(obligation.id) : externalObligationService.detail(obligation.id),
    [auditor, obligation.id],
  );
  const { data: detail, loading, error } = useResource(loader);
  const [retrying, setRetrying] = useState(false);
  const [retryError, setRetryError] = useState(null);

  const retry = async () => {
    setRetrying(true);
    setRetryError(null);
    try {
      onRetried(await externalObligationService.retry(obligation.id));
    } catch (caught) {
      setRetryError(caught.message);
    } finally {
      setRetrying(false);
    }
  };

  return (
    <Modal
      open
      size="xl"
      title={`Obligación ${obligation.externalReferenceId}`}
      description={`${obligation.sourceModule} · evento ${obligation.sourceEventId}`}
      onClose={onClose}
      footer={<><Button variant="secondary" onClick={onClose}>Cerrar</Button>{!auditor && detail?.status === "ERROR" && <Button variant="primary" loading={retrying} onClick={retry}>Reintentar procesamiento</Button>}</>}
    >
      {error && <Alert variant="error" title="No pudimos abrir la obligación">{error}</Alert>}
      {retryError && <Alert variant="error" title="No se pudo reintentar">{retryError}</Alert>}
      {loading && <p className="text-[13px] text-neutral-400">Cargando traza…</p>}
      {detail && (
        <>
          {detail.errorMessage && <Alert variant="error" title="Causa del error">{detail.errorMessage}</Alert>}
          <FieldGrid columns={4} items={[
            { label: "Referencia externa", value: detail.externalReferenceId },
            { label: "Evento origen", value: detail.sourceEventId, span: 2 },
            { label: "Estado", value: <StatusBadge status={detail.status} /> },
            { label: "Contribuyente externo", value: detail.externalTaxpayerId },
            { label: "Contribuyente local", value: detail.taxpayerId ? `#${detail.taxpayerId}` : "Sin vincular" },
            { label: "Concepto local", value: detail.taxConceptId ? `#${detail.taxConceptId}` : "Sin vincular" },
            { label: "Deuda generada", value: detail.debt ? `#${detail.debt.id} · ${labelFor(detail.debt.status)}` : "No generada" },
            { label: "Importe", value: formatCurrency(detail.amount) },
            { label: "Vencimiento", value: formatDate(detail.dueDate) },
            { label: "Recibida", value: formatDateTime(detail.receivedAt) },
            { label: "Procesada", value: detail.processedAt ? formatDateTime(detail.processedAt) : "Pendiente" },
            { label: "Reintentos", value: detail.retryCount },
          ]} />
          {auditor && <div><h3 className="mb-3 text-[13px] font-semibold text-[#0F2C59]">Historial de procesamiento</h3><HistoryTimeline entries={detail.history ?? []} /></div>}
        </>
      )}
    </Modal>
  );
}
