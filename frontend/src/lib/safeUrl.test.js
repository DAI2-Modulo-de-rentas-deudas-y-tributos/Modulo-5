import { describe, expect, it } from "vitest";
import { safeExternalUrl } from "./safeUrl.js";

describe("safeExternalUrl", () => {
  it.each(["https://docs.example.test/file.pdf", "http://docs.example.test/file.pdf"])("acepta %s", (value) => {
    expect(safeExternalUrl(value)).toBe(value);
  });

  it.each(["javascript:alert(1)", "data:text/html,x", "//docs.example.test/file", "https://user:pass@example.test", " https://example.test", null, ""])("rechaza URL insegura %s", (value) => {
    expect(safeExternalUrl(value)).toBeNull();
  });
});
