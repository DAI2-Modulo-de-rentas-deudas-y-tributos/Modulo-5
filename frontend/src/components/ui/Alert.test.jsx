import { cleanup, render, screen } from "@testing-library/react";
import { afterEach, describe, expect, it } from "vitest";
import Alert from "./Alert.jsx";

afterEach(cleanup);

describe("Alert accesible", () => {
  it("expone errores como alertas y oculta el icono decorativo", () => {
    render(<Alert variant="error" title="Error">No se pudo guardar.</Alert>);
    const alert = screen.getByRole("alert");
    expect(alert.getAttribute("aria-atomic")).toBe("true");
    expect(alert.querySelector("svg").getAttribute("aria-hidden")).toBe("true");
  });

  it("expone información no urgente como status", () => {
    render(<Alert variant="info">Actualizado.</Alert>);
    expect(screen.getByRole("status").textContent).toContain("Actualizado.");
  });
});
