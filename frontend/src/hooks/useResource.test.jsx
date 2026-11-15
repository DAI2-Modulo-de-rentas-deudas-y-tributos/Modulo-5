import { act, cleanup, renderHook, waitFor } from "@testing-library/react";
import { afterEach, describe, expect, it, vi } from "vitest";
import useResource from "./useResource.js";

afterEach(cleanup);

describe("useResource", () => {
  it("ignora una respuesta anterior que termina después de la recarga más reciente", async () => {
    let resolveInitial;
    let resolveReload;
    const loader = vi.fn()
      .mockImplementationOnce(() => new Promise((resolve) => { resolveInitial = resolve; }))
      .mockImplementationOnce(() => new Promise((resolve) => { resolveReload = resolve; }));
    const { result } = renderHook(() => useResource(loader));
    await waitFor(() => expect(loader).toHaveBeenCalledTimes(1));

    let reloadPromise;
    await act(async () => { reloadPromise = result.current.reload(); });
    await waitFor(() => expect(loader).toHaveBeenCalledTimes(2));
    await act(async () => { resolveReload("más reciente"); await reloadPromise; });
    await waitFor(() => expect(result.current.data).toBe("más reciente"));

    await act(async () => { resolveInitial("obsoleta"); await Promise.resolve(); });
    expect(result.current.data).toBe("más reciente");
    expect(result.current.loading).toBe(false);
  });
});
