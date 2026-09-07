import { describe, expect, it } from "vitest";
import { settlementService, taxConfigService } from "./rentasService.js";

/**
 * Configuración de tributos. En archivo propio: varias ramas amplían
 * `rentasService.test.js` y anexar al final del mismo archivo choca al mergear.
 */
describe("versiones de configuración", () => {
  it("cada concepto expone su versión vigente", async () => {
    const conceptos = await taxConfigService.list();
    const tasa = conceptos.find((c) => c.code === "TASA_SERVICIOS");

    expect(tasa.activeVersion.version).toBe(3);
    expect(tasa.activeVersion.rate).toBe(2);
    expect(tasa.pendingVersion).toBeNull();
  });

  it("la ficha informa cuántas liquidaciones y deudas dependen del concepto", async () => {
    const detalle = await taxConfigService.detail("TASA_SERVICIOS");

    expect(detalle.settlementCount).toBeGreaterThan(0);
    expect(detalle.versions.length).toBe(3);
  });

  it("valida las reglas de cálculo antes de aceptar la propuesta", async () => {
    await expect(
      taxConfigService.proposeVersion({
        code: "ABL",
        calculationType: "PORCENTAJE",
        rate: 0,
        validFrom: "2027-01-01",
        validUntil: "2027-12-31",
        note: "Sin alícuota",
        requestedBy: "mrivas",
      }),
    ).rejects.toThrow(/alícuota mayor a cero/i);

    await expect(
      taxConfigService.proposeVersion({
        code: "ABL",
        calculationType: "FIJO",
        minimumAmount: 900,
        maximumAmount: 100,
        validFrom: "2027-01-01",
        validUntil: "2027-12-31",
        note: "Topes al revés",
        requestedBy: "mrivas",
      }),
    ).rejects.toThrow(/máximo no puede ser menor/i);

    await expect(
      taxConfigService.proposeVersion({
        code: "ABL",
        calculationType: "FIJO",
        validFrom: "2027-12-31",
        validUntil: "2027-01-01",
        note: "Vigencia invertida",
        requestedBy: "mrivas",
      }),
    ).rejects.toThrow(/vigencia/i);
  });

  it("rechaza una vigencia que se solapa con la versión que rige", async () => {
    // COMMERCIAL_FINE rige hasta el 2026-12-31.
    await expect(
      taxConfigService.proposeVersion({
        code: "COMMERCIAL_FINE",
        calculationType: "IMPORTE_EXTERNO",
        validFrom: "2026-08-01",
        validUntil: "2027-07-31",
        note: "Se pisa con la vigente",
        requestedBy: "mrivas",
      }),
    ).rejects.toThrow(/tiene que empezar después/i);

    // Arrancando después sí entra.
    const version = await taxConfigService.proposeVersion({
      code: "COMMERCIAL_FINE",
      calculationType: "IMPORTE_EXTERNO",
      validFrom: "2027-01-01",
      validUntil: "2027-12-31",
      note: "Renovación de vigencia",
      requestedBy: "mrivas",
    });
    expect(version.status).toBe("DRAFT");
  });

  it("la versión nueva nace en borrador y no rige todavía", async () => {
    const version = await taxConfigService.proposeVersion({
      code: "TASA_SERVICIOS",
      calculationType: "PORCENTAJE",
      rate: 12,
      minimumAmount: 20000,
      maximumAmount: 200000,
      validFrom: "2027-01-01",
      validUntil: "2027-12-31",
      note: "Actualización de alícuota al 12%",
      requestedBy: "mrivas",
    });

    expect(version.status).toBe("DRAFT");
    // La vigente sigue siendo la anterior.
    const conceptos = await taxConfigService.list();
    expect(conceptos.find((c) => c.code === "TASA_SERVICIOS").activeVersion.rate).toBe(2);
  });

  it("no admite dos versiones en curso sobre el mismo concepto", async () => {
    await expect(
      taxConfigService.proposeVersion({
        code: "TASA_SERVICIOS",
        calculationType: "PORCENTAJE",
        rate: 15,
        validFrom: "2027-01-01",
        validUntil: "2027-12-31",
        note: "Otra más",
        requestedBy: "mrivas",
      }),
    ).rejects.toThrow(/ya tiene una versión en curso/i);
  });

  it("el borrador pasa a la bandeja del Supervisor", async () => {
    await taxConfigService.submitForApproval({
      code: "TASA_SERVICIOS",
      version: 4,
      requestedBy: "mrivas",
    });

    const bandeja = await taxConfigService.pendingApprovals();
    const propuesta = bandeja.find((v) => v.code === "TASA_SERVICIOS");

    expect(propuesta.version).toBe(4);
    expect(propuesta.currentVersion.version).toBe(3);
  });

  it("sólo el Supervisor aprueba", async () => {
    await expect(
      taxConfigService.resolveVersion({
        code: "TASA_SERVICIOS",
        version: 4,
        status: "APPROVED",
        resolvedBy: "mrivas",
        resolverRole: "PERSONAL",
      }),
    ).rejects.toThrow(/sólo el supervisor/i);
  });

  it("exige motivo al rechazar", async () => {
    await expect(
      taxConfigService.resolveVersion({
        code: "TASA_SERVICIOS",
        version: 4,
        status: "REJECTED",
        resolvedBy: "jlopez",
        resolverRole: "SUPERVISOR",
        reason: "",
      }),
    ).rejects.toThrow(/motivo/i);
  });

  it("al aprobar, la nueva rige y la anterior queda inactiva sin borrarse", async () => {
    const resultado = await taxConfigService.resolveVersion({
      code: "TASA_SERVICIOS",
      version: 4,
      status: "APPROVED",
      resolvedBy: "jlopez",
      resolverRole: "SUPERVISOR",
    });

    expect(resultado.status).toBe("ACTIVE");
    expect(resultado.previousVersion.version).toBe(3);
    expect(resultado.previousVersion.status).toBe("INACTIVE");
    // Los parámetros de la versión pasan a ser los del concepto.
    expect(resultado.concept.rate).toBe(12);

    // El historial completo se conserva.
    const detalle = await taxConfigService.detail("TASA_SERVICIOS");
    expect(detalle.versions.length).toBe(4);
  });
});

describe("efecto sobre las liquidaciones", () => {
  it("la liquidación queda sellada con la versión con la que se calculó", async () => {
    const antes = await settlementService.generate({
      taxpayerId: 78,
      conceptCode: "ABL",
      period: "2028-01",
      baseAmount: 50000,
      dueDate: "2028-01-15",
    });
    const versionUsada = antes.conceptVersion;

    // Cambia la configuración y se aprueba.
    const nueva = await taxConfigService.proposeVersion({
      code: "ABL",
      calculationType: "FIJO",
      minimumAmount: 130000,
      maximumAmount: 260000,
      validFrom: "2028-01-01",
      validUntil: "2028-12-31",
      note: "Actualización del valor fijo",
      requestedBy: "mrivas",
    });
    await taxConfigService.submitForApproval({
      code: "ABL",
      version: nueva.version,
      requestedBy: "mrivas",
    });
    await taxConfigService.resolveVersion({
      code: "ABL",
      version: nueva.version,
      status: "APPROVED",
      resolvedBy: "jlopez",
      resolverRole: "SUPERVISOR",
    });

    // La liquidación anterior conserva su versión: el cambio no reescribe el pasado.
    const emitidas = await settlementService.list({ period: "2028-01" });
    expect(emitidas.find((s) => s.id === antes.id).conceptVersion).toBe(versionUsada);

    // La siguiente ya usa la nueva.
    const despues = await settlementService.generate({
      taxpayerId: 78,
      conceptCode: "ABL",
      period: "2028-02",
      baseAmount: 50000,
      dueDate: "2028-02-15",
    });
    expect(despues.conceptVersion).toBe(nueva.version);
  });

  it("un concepto se puede desactivar y sus deudas siguen vigentes", async () => {
    const version = await taxConfigService.proposeVersion({
      code: "PATENTE",
      calculationType: "PORCENTAJE",
      rate: 1.5,
      validFrom: "2028-01-01",
      validUntil: "2028-12-31",
      conceptStatus: "INACTIVE",
      note: "Baja del concepto",
      requestedBy: "mrivas",
    });
    await taxConfigService.submitForApproval({
      code: "PATENTE",
      version: version.version,
      requestedBy: "mrivas",
    });
    await taxConfigService.resolveVersion({
      code: "PATENTE",
      version: version.version,
      status: "APPROVED",
      resolvedBy: "jlopez",
      resolverRole: "SUPERVISOR",
    });

    const detalle = await taxConfigService.detail("PATENTE");
    expect(detalle.status).toBe("INACTIVE");
    // No se borró: las liquidaciones y deudas que lo referencian siguen ahí.
    expect(detalle.settlementCount).toBeGreaterThan(0);
  });
});
