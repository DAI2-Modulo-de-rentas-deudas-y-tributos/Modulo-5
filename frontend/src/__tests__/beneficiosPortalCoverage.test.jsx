import { afterEach, describe, expect, it, vi } from "vitest";
import { cleanup, render, screen, waitFor, within } from "@testing-library/react";
import { MemoryRouter } from "react-router-dom";
import BeneficiosPortalPage from "../pages/portal/BeneficiosPortalPage.jsx";
import { portalService } from "../services/rentasService.js";

vi.mock("../context/AuthContext.jsx", () => ({
  useAuth: () => ({ user: { role: "TAXPAYER", taxpayerId: 42 } }),
}));

const dateOffset = (offset) => {
  const date = new Date();
  date.setUTCDate(date.getUTCDate() + offset);
  return date.toISOString().slice(0, 10);
};

function renderPage() {
  return render(
    <MemoryRouter>
      <BeneficiosPortalPage />
    </MemoryRouter>,
  );
}

afterEach(() => {
  cleanup();
  vi.restoreAllMocks();
});

describe("BeneficiosPortalPage", () => {
  it("consulta sólo los beneficios del contribuyente autenticado y muestra el vigente con sus conceptos", async () => {
    const benefits = vi.spyOn(portalService, "benefits").mockResolvedValue([
      {
        id: "active",
        taxpayerId: 42,
        status: "ACTIVE",
        benefitType: "SOCIAL_ASSISTANCE",
        externalBenefitId: "BEN-OWN-1",
        discountPercentage: 50,
        validFrom: dateOffset(0),
        validUntil: dateOffset(2),
        taxConceptCodes: ["TASA_SERVICIOS", "ABL"],
      },
      {
        id: "future",
        status: "ACTIVE",
        externalBenefitId: "BEN-FUTURE",
        validFrom: dateOffset(1),
        validUntil: dateOffset(10),
        taxConceptCodes: ["ABL"],
      },
      {
        id: "expired",
        status: "ACTIVE",
        externalBenefitId: "BEN-EXPIRED",
        validFrom: dateOffset(-10),
        validUntil: dateOffset(-1),
        taxConceptCodes: ["ABL"],
      },
      {
        id: "inactive",
        status: "INACTIVE",
        externalBenefitId: "BEN-INACTIVE",
        validFrom: dateOffset(-1),
        validUntil: dateOffset(10),
        taxConceptCodes: ["ABL"],
      },
    ]);

    renderPage();

    expect(await screen.findByText("BEN-OWN-1")).toBeTruthy();
    expect(benefits).toHaveBeenCalledTimes(1);
    expect(benefits).toHaveBeenCalledWith({ taxpayerId: 42 });
    expect(screen.getByText("TASA_SERVICIOS, ABL")).toBeTruthy();
    expect(screen.getByText("50%")).toBeTruthy();

    const table = screen.getByRole("table");
    expect(within(table).getAllByRole("row")).toHaveLength(2);
    expect(screen.queryByText("BEN-FUTURE")).toBeNull();
    expect(screen.queryByText("BEN-EXPIRED")).toBeNull();
    expect(screen.queryByText("BEN-INACTIVE")).toBeNull();
  });

  it("expone un error de servicio al contribuyente cuando falla la consulta", async () => {
    vi.spyOn(portalService, "benefits").mockRejectedValue(new Error("No pudimos consultar los beneficios."));

    renderPage();

    expect(await screen.findByText("No pudimos consultar los beneficios.")).toBeTruthy();
    expect(screen.getByText("No pudimos cargar tus beneficios")).toBeTruthy();
    await waitFor(() => expect(screen.getByText("Sin beneficios vigentes")).toBeTruthy());
  });
});
