import { useCallback, useState } from "react";
import { useNavigate } from "react-router-dom";
import { Download, Search } from "lucide-react";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Button from "../../components/common/Button.jsx";
import Alert from "../../components/ui/Alert.jsx";
import useResource from "../../hooks/useResource.js";
import useTaxpayerIndex from "../../hooks/useTaxpayerIndex.js";
import { billService } from "../../services/rentasService.js";
import { formatCurrency, formatDate, formatDateTime } from "../../lib/format.js";

/**
 * Boletas: se busca por el número que trae el contribuyente. Desde el resultado se
 * imprime el documento o se cobra directamente, saltando al flujo de Cobros.
 */
export default function BoletasCajaPage() {
  const navigate = useNavigate();
  const [query, setQuery] = useState("");
  const [submitted, setSubmitted] = useState("");

  const loader = useCallback(
    () => (submitted.trim() ? billService.search({ query: submitted }) : Promise.resolve([])),
    [submitted],
  );
  const { data: bills, loading, error } = useResource(loader, []);
  const { nameOf } = useTaxpayerIndex();

  const columns = [
    {
      key: "id",
      header: "N° de boleta",
      render: (row) => (
        <div className="flex flex-col">
          <span className="font-medium tabular-nums text-neutral-800">#{row.id}</span>
          <span className="text-[12px] tabular-nums text-neutral-400">{row.barcode}</span>
        </div>
      ),
    },
    { key: "taxpayer", header: "Contribuyente", render: (row) => nameOf(row.taxpayerId) },
    { key: "conceptCode", header: "Concepto" },
    { key: "dueDate", header: "Vencimiento", render: (row) => formatDate(row.dueDate) },
    {
      key: "amount",
      header: "Importe",
      align: "right",
      render: (row) => formatCurrency(row.amount),
    },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
    {
      key: "issuedAt",
      header: "Emitida",
      render: (row) => formatDateTime(row.issuedAt),
    },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (row) => (
        <div className="flex items-center justify-end gap-3">
          <a
            href={row.documentUrl}
            title={row.documentUrl}
            className="inline-flex items-center gap-1.5 text-[12px] font-semibold text-[#0F2C59] transition-colors hover:text-[#D63031]"
          >
            <Download className="h-3.5 w-3.5" strokeWidth={2} />
            Imprimir
          </a>
          {row.status !== "SETTLED" && (
            <Button
              size="sm"
              variant="primary"
              onClick={() => navigate(`/caja/cobros?boleta=${row.id}`)}
            >
              Registrar pago
            </Button>
          )}
        </div>
      ),
    },
  ];

  return (
    <ModuleShell
      label="Ventanilla"
      title="Boletas"
      highlight="de pago"
      description="Buscá la boleta por su número o código de barras para imprimirla o cobrarla."
      breadcrumb={[{ id: "boletas", label: "Boletas" }]}
      homePath="/caja"
      homeLabel="Panel de caja"
    >
      {error && (
        <Alert variant="error" title="No pudimos buscar la boleta">
          {error}
        </Alert>
      )}

      <Card
        title="Buscar boleta"
        description="Por número de boleta o por el código de barras impreso."
      >
        <form
          onSubmit={(event) => {
            event.preventDefault();
            setSubmitted(query);
          }}
          className="flex flex-col gap-3 border-b border-neutral-100 px-5 py-4 sm:flex-row sm:items-center"
        >
          <div className="relative flex-1">
            <Search
              className="pointer-events-none absolute left-3 top-1/2 h-4 w-4 -translate-y-1/2 text-neutral-400"
              strokeWidth={2}
            />
            <input
              type="search"
              value={query}
              onChange={(event) => setQuery(event.target.value)}
              placeholder="N° de boleta"
              aria-label="N° de boleta"
              className="w-full rounded-lg border border-neutral-200 bg-neutral-50 py-2.5 pl-9 pr-3 text-[14px] text-neutral-900 placeholder-neutral-400 outline-none transition-colors focus:border-[#D63031]/40 focus:bg-white focus:ring-2 focus:ring-[#D63031]/10"
            />
          </div>
          <Button type="submit" variant="primary">
            Buscar
          </Button>
        </form>

        {submitted.trim() ? (
          <DataTable
            columns={columns}
            rows={bills ?? []}
            rowKey={(row) => row.id}
            loading={loading}
            emptyIconName="FileSearch"
            emptyTitle="Sin resultados"
            emptyDescription="Revisá el número impreso en la boleta."
          />
        ) : (
          <p className="px-5 py-10 text-center text-[13px] text-neutral-400">
            Ingresá el número de la boleta que trae el contribuyente.
          </p>
        )}
      </Card>
    </ModuleShell>
  );
}
