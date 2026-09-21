import { useCallback } from "react";
import ModuleShell from "../../components/layout/ModuleShell.jsx";
import Card from "../../components/common/Card.jsx";
import DataTable from "../../components/common/DataTable.jsx";
import StatusBadge from "../../components/common/StatusBadge.jsx";
import Alert from "../../components/ui/Alert.jsx";
import useResource from "../../hooks/useResource.js";
import { portalService } from "../../services/rentasService.js";
import { useAuth } from "../../context/AuthContext.jsx";
import { formatDate, formatPercentage, labelFor } from "../../lib/format.js";

export default function BeneficiosPortalPage() {
  const { user } = useAuth();
  const loader = useCallback(() => portalService.benefits({ taxpayerId: user.taxpayerId }), [user.taxpayerId]);
  const { data: benefits, loading, error } = useResource(loader, []);
  const active = (benefits ?? []).filter((benefit) => benefit.status === "ACTIVE");

  return (
    <ModuleShell
      label="Mi cuenta"
      title="Beneficios tributarios"
      highlight="vigentes"
      description="Beneficios informados por Desarrollo Social y su alcance en Rentas."
      breadcrumb={[{ id: "beneficios", label: "Beneficios tributarios" }]}
      homePath="/portal"
      homeLabel="Inicio"
    >
      {error && <Alert variant="error" title="No pudimos cargar tus beneficios">{error}</Alert>}
      <Alert variant="info" title="Aplicación automática">
        Cuando un beneficio está vigente, Rentas lo considera al calcular las liquidaciones alcanzadas. No necesitás solicitarlo otra vez desde este portal.
      </Alert>
      <Card title={`Beneficios vigentes (${active.length})`} description="Tipo, porcentaje, vigencia y referencia del sistema que lo otorgó.">
        <DataTable
          columns={[
            { key: "benefitType", header: "Tipo", render: (row) => labelFor(row.benefitType) },
            { key: "externalBenefitId", header: "Referencia", render: (row) => row.externalBenefitId },
            { key: "discountPercentage", header: "Descuento", align: "right", render: (row) => formatPercentage(row.discountPercentage) },
            { key: "validFrom", header: "Desde", render: (row) => formatDate(row.validFrom) },
            { key: "validUntil", header: "Hasta", render: (row) => row.validUntil ? formatDate(row.validUntil) : "Sin vencimiento" },
            { key: "status", header: "Estado", render: (row) => <StatusBadge status={row.status} /> },
          ]}
          rows={active}
          rowKey={(row) => row.id}
          loading={loading}
          emptyIconName="BadgePercent"
          emptyTitle="Sin beneficios vigentes"
          emptyDescription="No hay beneficios tributarios activos asociados a tu cuenta."
        />
      </Card>
    </ModuleShell>
  );
}
