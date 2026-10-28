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

    const action = screen.getByRole("button", { name: "Activar fila" });
    await user.click(action);
    expect(onRowClick).toHaveBeenCalledTimes(1);
    onRowClick.mockClear();
    action.blur();
    await user.tab();
    expect(document.activeElement).toBe(action);
    await user.keyboard(key === "Enter" ? "{Enter}" : " ");

    expect(onRowClick).toHaveBeenCalledWith(rows[0]);
    expect(onRowClick).toHaveBeenCalledTimes(1);
  });

  it("no crea acciones de teclado en tablas de sólo lectura", () => {
    render(<DataTable columns={columns} rows={rows} rowKey={(row) => row.id} />);
    expect(screen.queryByRole("button")).toBeNull();
  });

  it("no activa la fila al usar un control interactivo contenido", async () => {
    const user = userEvent.setup();
    const onRowClick = vi.fn();
    const actionColumns = [{
      key: "name",
      header: "Nombre",
      render: () => <button type="button">Acción propia</button>,
    }];
    render(<DataTable columns={actionColumns} rows={rows} rowKey={(row) => row.id} onRowClick={onRowClick} />);

    await user.click(screen.getByRole("button", { name: "Acción propia" }));

    expect(onRowClick).not.toHaveBeenCalled();
  });
});
