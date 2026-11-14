export function sanitizeProxyForwardedFor(proxyRequest, request) {
  proxyRequest.removeHeader("X-Forwarded-For");
  const address = request.socket?.remoteAddress;
  if (address) proxyRequest.setHeader("X-Forwarded-For", address.replace(/^::ffff:/, ""));
}
