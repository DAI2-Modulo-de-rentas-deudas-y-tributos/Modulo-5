import assert from "node:assert/strict";
import { execFileSync, spawn } from "node:child_process";
import { randomUUID } from "node:crypto";
import { mkdtempSync, readFileSync, rmSync, rmdirSync } from "node:fs";
import { createServer } from "node:http";
import { tmpdir } from "node:os";
import { join } from "node:path";
import { fileURLToPath } from "node:url";
import { setTimeout as sleep } from "node:timers/promises";
import { chromium, expect } from "@playwright/test";

const frontend = fileURLToPath(new URL("../", import.meta.url));
const jar = fileURLToPath(new URL("../../backend/target/rentas-backend.jar", import.meta.url));
const certificate = mkdtempSync(join(tmpdir(), "m5-citizen-https-"));
const key = join(certificate, "key.pem");
const cert = join(certificate, "cert.pem");
const id = randomUUID();
const other = randomUUID();
const db = "m5-citizen-e2e-" + randomUUID();
const password = randomUUID();
const cookieName = "__Host-rentas-citizen";
const loopback = "127.0.0.1";
const listen = (server) => new Promise(resolve => server.listen(0, loopback, () => resolve(server.address().port)));
const command = (bin, args, input) => execFileSync(bin, args, { encoding: "utf8", input }).trim();
const sql = (input) => command("docker", ["exec", "-i", db, "psql", "-U", "rentas", "-d", "rentas",
  "-Atq", "-v", "ON_ERROR_STOP=1"], input);
const m1 = createServer(async (request, response) => {
  try {
    assert.equal(request.url, "/auth/usuario/login");
    assert.equal(request.method, "POST");
    assert.equal(request.headers["x-forwarded-for"], undefined);
    let raw = "";
    for await (const chunk of request) raw += chunk;
    const credentials = JSON.parse(raw);
    if (credentials.gmail !== "citizen@example.invalid" || credentials.password !== "synthetic-only") {
      response.writeHead(401).end();
      return;
    }
    response.writeHead(200, { "Content-Type": "application/json" }).end(JSON.stringify({
      auth: true, actorType: "CIUDADANO", role: "CIUDADANO", uuid: id, actorId: id, citizenId: id,
    }));
  } catch {
    response.writeHead(400).end();
  }
});
let vite, backend, browser, databaseCreated;
let backendExit;
try {
  command(process.env.OPENSSL_BIN || "openssl", ["req", "-x509", "-newkey", "rsa:2048", "-nodes",
    "-days", "1", "-subj", "/CN=localhost", "-addext", "subjectAltName=IP:127.0.0.1",
    "-keyout", key, "-out", cert]);
  const m1Port = await listen(m1);
  command("docker", ["run", "--rm", "-d", "--name", db, "-p", "127.0.0.1::5432",
    "-e", "POSTGRES_USER=rentas", "-e", "POSTGRES_DB=rentas", "-e", "POSTGRES_PASSWORD=" + password,
    "postgres:16.13-alpine"]);
  databaseCreated = true;
  const dbPort = command("docker", ["port", db, "5432/tcp"]).split(":").at(-1);
  backend = spawn(process.env.JAVA_HOME ? join(process.env.JAVA_HOME, "bin", "java") : "java", [
    "-jar", jar, "--server.address=127.0.0.1", "--server.port=0",
  ], { env: {
    PATH: process.env.PATH, SystemRoot: process.env.SystemRoot, SPRING_PROFILES_ACTIVE: "citizen-auth-local",
    DB_URL: "jdbc:postgresql://127.0.0.1:" + dbPort + "/rentas", DB_USER: "rentas", DB_PASSWORD: password,
    BROKER_ADAPTER: "local-log", CORE_RABBITMQ_CONSUMER_ENABLED: "false", RENTAS_SECURITY_DEV_MODE: "false",
    RENTAS_CITIZEN_AUTH_ENABLED: "true", RENTAS_CITIZEN_AUTH_BASE_URL: "http://127.0.0.1:" + m1Port,
  }, stdio: ["ignore", "pipe", "inherit"] });
  let backendPort;
  backend.on("error", error => { backendExit = error.message; });
  let startup = "";
  backend.stdout.on("data", chunk => {
    startup = (startup + chunk).slice(-16_384);
    backendPort ??= startup.match(/Tomcat started on port (\d+)/)?.[1];
  });
  for (let i = 0; i < 240; i++) {
    if (backendExit || backend.exitCode !== null) throw new Error(backendExit || startup);
    if (backendPort) {
      const health = await fetch("http://127.0.0.1:" + backendPort + "/actuator/health").catch(() => null);
      if (health?.ok) break;
    }
    if (i === 239) throw new Error("Backend local no quedó listo: " + startup);
    await sleep(500);
  }
  const [owner, stranger] = sql(`
    INSERT INTO taxpayer_reference(taxpayer_type,external_id,display_name,external_status,
      canonical_citizen_id,created_at,updated_at)
    VALUES ('CITIZEN','browser-owner','Browser owner','ACTIVE','${id}',now(),now()),
           ('CITIZEN','browser-stranger','Browser stranger','ACTIVE','${other}',now(),now())
    RETURNING id;
  `).split(/\r?\n/);
  process.env.API_PROXY_TARGET = "http://127.0.0.1:" + backendPort;
  process.env.VITE_AUTH_MODE = "citizen";
  process.env.VITE_API_BASE_URL = "";
  const { createServer: createVite } = await import("vite");
  vite = await createVite({ root: frontend, server: { host: loopback, port: 0,
    https: { key: readFileSync(key), cert: readFileSync(cert) } } });
  await vite.listen();
  const base = "https://127.0.0.1:" + vite.httpServer.address().port;
  browser = await chromium.launch();
  const context = await browser.newContext({ ignoreHTTPSErrors: true });
  const page = await context.newPage();
  const cookieRejections = [];
  const authResponses = [];
  page.on("response", response => {
    const path = new URL(response.url()).pathname;
    if (path.startsWith("/api/v1/citizen-auth/")) authResponses.push(path + ":" + response.status());
  });
  const cdp = await context.newCDPSession(page);
  await cdp.send("Network.enable");
  cdp.on("Network.responseReceivedExtraInfo", ({ blockedCookies }) => {
    for (const { blockedReasons } of blockedCookies ?? []) cookieRejections.push(...blockedReasons);
  });
  const me = () => context.request.get(base + "/api/v1/citizen-auth/me");
  const login = async () => {
    await page.goto(base + "/login");
    await page.getByRole("textbox", { name: /Correo de Ciudadanos/ }).fill("citizen@example.invalid");
    await page.locator('input[name="password"]').fill("synthetic-only");
    const loginResponse = page.waitForResponse(response =>
      response.url().endsWith("/api/v1/citizen-auth/login") && response.request().method() === "POST");
    await page.getByRole("button", { name: "Ingresar", exact: true }).click();
    const response = await loginResponse;
    assert.equal(response.status(), 200);
    const cookieHeader = await response.headerValue("set-cookie");
    assert.ok(cookieHeader?.startsWith(cookieName + "="), "login must return the session cookie");
    assert.match(cookieHeader.slice(cookieHeader.indexOf(";")), /Max-Age=600/i);
    await expect(page).toHaveURL(/\/portal\/?$/);
  };
  await login();
  const browserSessionStatus = await page.evaluate(async () => (await fetch("/api/v1/citizen-auth/me")).status);
  const cookie = (await context.cookies()).find(c => c.name === cookieName);
  assert.ok(cookie?.httpOnly && cookie.secure && cookie.sameSite === "Strict" && cookie.path === "/",
    "Chromium did not retain the secure cookie. " + JSON.stringify({ cookieRejections, authResponses, browserSessionStatus }));
  assert.equal(await page.evaluate(name => document.cookie.includes(name), cookieName), false);
  await page.reload();
  await page.waitForURL(/\/portal\/?$/);
  assert.equal((await me()).status(), 200);
  assert.equal((await context.request.get(base + "/api/v1/taxpayers/" + owner + "/debts")).status(), 200);
  assert.equal((await context.request.get(base + "/api/v1/taxpayers/" + stranger + "/debts")).status(), 403);
  assert.equal((await context.request.get(base + "/api/v1/taxpayers")).status(), 403);
  await page.goto(base + "/rentas/deudas");
  await page.waitForURL(/\/portal\/?$/);
  const denied = await page.evaluate(async () => (await fetch("/api/v1/citizen-auth/logout", { method: "POST" })).status);
  assert.equal(denied, 403);
  await page.getByRole("button", { name: /opciones de cuenta/ }).click();
  await page.getByRole("menuitem", { name: "Cerrar sesión" }).click();
  await page.waitForURL(/\/login$/);
  assert.equal((await me()).status(), 401);
  assert.equal((await context.request.get(base + "/api/v1/citizen-auth/me", {
    headers: { Cookie: cookieName + "=" + cookie.value },
  })).status(), 401);
  await login();
  sql("UPDATE citizen_session SET expires_at=now()-interval '1 second';");
  assert.equal((await me()).status(), 401);
  await page.reload();
  await page.waitForURL(/\/login$/);
  console.log("PASS Chromium HTTPS: cookie, recarga, titularidad, roles, CSRF, logout y expiración.");
} finally {
  await browser?.close();
  await vite?.close();
  backend?.kill();
  m1.close();
  if (databaseCreated) command("docker", ["stop", db]);
  rmSync(key, { force: true });
  rmSync(cert, { force: true });
  rmdirSync(certificate);
}
