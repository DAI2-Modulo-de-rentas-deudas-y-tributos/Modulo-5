import { describe, expect, it } from "vitest";
import { sanitizeProxyForwardedFor } from "./src/lib/sanitizeProxyForwardedFor.js";

describe("API proxy client address", () => {
  function forward(headers, remoteAddress) {
    const proxyRequest = {
      setHeader: (name, value) => headers.set(name.toLowerCase(), value),
      removeHeader: (name) => headers.delete(name.toLowerCase()),
    };
    sanitizeProxyForwardedFor(proxyRequest, { socket: { remoteAddress } });
  }

  it("replaces a client supplied X-Forwarded-For with the proxy peer address", () => {
    const headers = new Map([["x-forwarded-for", "198.51.100.200"]]);

    forward(headers, "::ffff:203.0.113.8");

    expect(headers.get("x-forwarded-for")).toBe("203.0.113.8");
  });

  it("removes a forged forwarded address when the peer address is unavailable", () => {
    const headers = new Map([["x-forwarded-for", "198.51.100.200"]]);

    forward(headers, undefined);

    expect(headers.has("x-forwarded-for")).toBe(false);
  });
});
