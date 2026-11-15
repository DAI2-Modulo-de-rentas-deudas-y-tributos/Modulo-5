import { useCallback, useState } from "react";
import { Printer } from "lucide-react";
import BillPdfDownload from "../../components/documentos/BillPdfDownload.jsx";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import FilterBar from "../../components/common/FilterBar.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Modal from "../../components/common/Modal.jsx";
import Button from "../../components/common/Button.jsx";
import Alert from "../../components/ui/Alert.jsx";
import BoletaDocument, { printBoleta } from "../../components/documentos/BoletaDocument.jsx";
import useResource from "../../hooks/useResource.js";
import { portalService } from "../../services/rentasService.js";
import { useAuth } from "../../context/AuthContext.jsx";
import { formatCurrency, formatDate } from "../../lib/format.js";

/** Boletas emitidas al contribuyente, para descargar y pagar por los canales habilitados. */
export default function MisBoletasPage() {
  const { user } = useAuth();
  const [status, setStatus] = useState("");
  const [selected, setSelected] = useState(null);

  const loader = useCallback(
    () => portalService.bills({ taxpayerId: user.taxpayerId, status }),
    [user.taxpayerId, status],
  );
  const { data: bills, loading, error } = useResource(loader, []);

  // La boleta impresa lleva tipo y número de documento, que no están en la sesión.
  const profileLoader = useCallback(
    () => portalService.accountSummary(user.taxpayerId),
    [user.taxpayerId],
  );
  const { data: account } = useResource(profileLoader);

  const columns = [
    {
      key: "id",
      header: "Boleta",
      render: (row) => (
        <div className="flex flex-col">
          <span className="font-medium tabular-nums text-neutral-800">#{row.id}</span>
          <span className="text-[12px] tabular-nums text-neutral-400">{row.barcode}</span>
        </div>
      ),
    },
    { key: "conceptName", header: "Concepto" },
    {
      key: "dueDate",
      header: "Vencimiento",
      render: (row) => (
        <div className="flex flex-col">
          <span>{formatDate(row.dueDate)}</span>
          {row.status === "ISSUED" && (
            <span className="text-[12px] text-neutral-400">
              {row.daysLeft < 0
                ? `Vencida hace ${Math.abs(row.daysLeft)} días`
                : row.daysLeft === 0
                  ? "Vence hoy"
                  : `En ${row.daysLeft} días`}
            </span>
          )}
        </div>
      ),
    },
    {
      key: "amount",
      header: "Importe",
      align: "right",
      render: (row) => formatCurrency(row.amount),
    },
    { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
    { key: "issuedAt", header: "Emitida", render: (row) => formatDate(row.issuedAt) },
    {
      key: "actions",
      header: "",
      align: "right",
      render: (row) => (
        <div className="flex items-center justify-end gap-3">
          <button
            type="button"
            onClick={() => setSelected(row)}
            className="inline-flex items-center gap-1.5 text-[12px] font-semibold text-[#0F2C59] transition-colors hover:text-[#D63031]"
          >
            <Printer className="h-3.5 w-3.5" strokeWidth={2} />
            Ver e imprimir
          </button>
          <BillPdfDownload billId={row.id} />
        </div>
      ),
    },
  ];

  return (
    <ModuleShell
      label="Mi cuenta"
      title="Mis boletas"
      highlight="de pago"
      description="Las boletas emitidas a tu nombre, listas para descargar y abonar."
      breadcrumb={[{ id: "boletas", label: "Mis boletas" }]}
      homePath="/portal"
      homeLabel="Inicio"
    >
      {error && (
        <Alert variant="error" title="No pudimos cargar tus boletas">
          {error}
        </Alert>
      )}

      <Card
        title="Boletas emitidas"
        description="Con el código de barras podés pagar en cualquier canal habilitado."
      >
        <FilterBar
          filters={[
            {
              name: "status",
              label: "Estado",
              options: [
                { value: "ISSUED", label: "Emitida" },
                { value: "SETTLED", label: "Cancelada" },
                { value: "EXPIRED", label: "Vencida" },
              ],
            },
          ]}
          values={{ status }}
          onFilterChange={(_, value) => setStatus(value)}
        />
        <DataTable
          columns={columns}
          rows={bills ?? []}
          rowKey={(row) => row.id}
          loading={loading}
          emptyIconName="FileText"
          emptyTitle="Sin boletas"
          emptyDescription="Todavía no se emitieron boletas a tu nombre con ese estado."
          onRowClick={setSelected}
        />
      </Card>

      <Alert variant="info" title="¿No ves la boleta que esperabas?">
        Las boletas las emite la Dirección General de Rentas a partir de una deuda con
        saldo. Si tenés una deuda sin boleta, acercate a la oficina o esperá la emisión
        del período.
      </Alert>

      <BoletaModal
        bill={selected}
        taxpayer={account?.taxpayer}
        onClose={() => setSelected(null)}
      />
    </ModuleShell>
  );
}

/** Vista imprimible de la boleta, para llevarla al canal de cobro. */
function BoletaModal({ bill, taxpayer, onClose }) {
  if (!bill) return null;

  return (
    <Modal
      open
      title={`Boleta #${bill.id}`}
      description="Imprimila y presentala en cualquier canal de cobro habilitado."
      onClose={onClose}
      footer={
        <>
          <Button variant="secondary" onClick={onClose}>
            Cerrar
          </Button>
          <Button variant="primary" onClick={printBoleta}>
            <Printer className="h-4 w-4" strokeWidth={2} />
            Imprimir
          </Button>
        </>
      }
    >
      <BoletaDocument bill={bill} taxpayer={taxpayer} />
    </Modal>
  );
}
