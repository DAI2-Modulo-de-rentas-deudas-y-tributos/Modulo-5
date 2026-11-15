import { afterEach, expect, it, vi } from "vitest";
import { cleanup, fireEvent, render, screen } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { useState } from "react";
import FileUpload from "../components/common/FileUpload.jsx";

function ControlledUpload({ maxFiles = 2 }) {
  const [files, setFiles] = useState([]);
  return <FileUpload files={files} onChange={setFiles} maxFiles={maxFiles} />;
}

afterEach(cleanup);

it("abre selector, acepta adjuntos y permite quitarlos", async () => {
  const user = userEvent.setup();
  render(<ControlledUpload />);
  const input = screen.getByLabelText("Documentación respaldatoria");
  const click = vi.spyOn(input, "click");
  await user.click(screen.getByRole("button", { name: "Adjuntar archivo" }));
  expect(click).toHaveBeenCalledOnce();

  const file = new File(["contenido"], "constancia.pdf", { type: "application/pdf" });
  fireEvent.change(input, { target: { files: [file] } });
  expect(await screen.findByText("constancia.pdf")).toBeDefined();
  expect(screen.getByText("PDF, JPG o PNG. Hasta 5 MB por archivo.")).toBeDefined();
  await user.click(screen.getByRole("button", { name: "Quitar constancia.pdf" }));
  expect(screen.queryByText("constancia.pdf")).toBeNull();
});

it("rechaza archivos mayores a 5 MB y exceder el máximo de archivos", async () => {
  const large = new File([new Uint8Array(5 * 1024 * 1024 + 1)], "grande.pdf", { type: "application/pdf" });
  const { rerender } = render(<ControlledUpload maxFiles={1} />);
  let input = screen.getByLabelText("Documentación respaldatoria");
  fireEvent.change(input, { target: { files: [large] } });
  expect(await screen.findByText("grande.pdf supera los 5 MB.")).toBeDefined();
  expect(screen.queryByText("grande.pdf")).toBeNull();

  const existing = new File(["a"], "uno.pdf", { type: "application/pdf" });
  const nextInput = screen.getByLabelText("Documentación respaldatoria");
  fireEvent.change(nextInput, { target: { files: [existing] } });
  expect(await screen.findByText("uno.pdf")).toBeDefined();
  rerender(<ControlledUpload maxFiles={1} />);
  input = screen.getByLabelText("Documentación respaldatoria");
  fireEvent.change(input, { target: { files: [new File(["b"], "dos.pdf", { type: "application/pdf" })] } });
  expect(await screen.findByText("Podés adjuntar hasta 1 archivos.")).toBeDefined();
  expect(screen.getByText("uno.pdf")).toBeDefined();
});
