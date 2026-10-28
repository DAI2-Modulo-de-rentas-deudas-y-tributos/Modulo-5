import { cleanup, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { afterEach, describe, expect, it, vi } from "vitest";
import DataTable from "./DataTable.jsx";

afterEach(cleanup);

const columns = [{ key: "name", header: "Nombre" }];
const rows = [{ id: "debt-1", name: "Deuda municipal" }];

describe("DataTable", () => {
  it.each(["Enter", " "])("activa una fila con %s", async (key) => {
    const user = userEvent.setup();
    const onRowClick = vi.fn();
    render(<DataTable columns={columns} rows={rows} rowKey={(row) => row.id} onRowClick={onRowClick} />);

    const row = screen.getByRole("row", { name: /Deuda municipal/ });
    expect(row.tabIndex).toBe(0);
    row.focus();
    await user.keyboard(key === "Enter" ? "{Enter}" : " ");

    expect(onRowClick).toHaveBeenCalledWith(rows[0]);
  });

  it("no convierte las filas de sólo lectura en controles de teclado", () => {
    render(<DataTable columns={columns} rows={rows} rowKey={(row) => row.id} />);
    expect(screen.getByRole("row", { name: /Deuda municipal/ }).tabIndex).toBe(-1);
  });
});
