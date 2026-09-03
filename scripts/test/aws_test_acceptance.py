#!/usr/bin/env python3
"""Acceptance checks for the deployed AWS TEST API using only the Python standard library."""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import uuid
from dataclasses import dataclass
from datetime import date, timedelta
from pathlib import Path
from typing import Any
from urllib.error import HTTPError, URLError
from urllib.parse import urlencode, urlsplit, urlunsplit
from urllib.request import Request, urlopen


@dataclass
class HttpResponse:
    status: int
    headers: Any
    body: bytes


class AcceptanceFailure(RuntimeError):
    pass


class ApiClient:
    def __init__(self, api_base_url: str, bearer_token: str | None) -> None:
        self.api_base_url = api_base_url.rstrip("/")
        self.bearer_token = bearer_token.strip() if bearer_token else None

    def _headers(self, has_body: bool) -> dict[str, str]:
        headers = {"Accept": "application/json"}
        if has_body:
            headers["Content-Type"] = "application/json"
        if self.bearer_token:
            headers["Authorization"] = f"Bearer {self.bearer_token}"
        else:
            headers["X-Dev-User"] = "qa-automation"
            headers["X-Dev-Roles"] = "RENTAS,SUPERVISOR,CASHIER"
        return headers

    def request(
        self,
        method: str,
        path: str,
        payload: Any | None = None,
        query: dict[str, Any] | None = None,
        expected: tuple[int, ...] | None = (200,),
    ) -> HttpResponse:
        url = f"{self.api_base_url}/{path.lstrip('/')}"
        if query:
            url = f"{url}?{urlencode(query, doseq=True)}"
        encoded = None if payload is None else json.dumps(payload).encode("utf-8")
        request = Request(url, data=encoded, method=method, headers=self._headers(encoded is not None))
        try:
            with urlopen(request, timeout=60) as response:
                result = HttpResponse(response.status, response.headers, response.read())
        except HTTPError as error:
            result = HttpResponse(error.code, error.headers, error.read())
        except URLError as error:
            raise AcceptanceFailure(f"No se pudo conectar con {url}: {error.reason}") from error

        if expected is not None and result.status not in expected:
            body = result.body.decode("utf-8", errors="replace")[:1500]
            raise AcceptanceFailure(
                f"{method} {path} devolvió HTTP {result.status}; esperado {expected}. Respuesta: {body}"
            )
        return result

    def json(
        self,
        method: str,
        path: str,
        payload: Any | None = None,
        query: dict[str, Any] | None = None,
        expected: tuple[int, ...] = (200,),
    ) -> Any:
        response = self.request(method, path, payload, query, expected)
        if not response.body:
            return None
        try:
            return json.loads(response.body)
        except json.JSONDecodeError as error:
            raise AcceptanceFailure(f"{method} {path} no devolvió JSON válido") from error


def require(condition: bool, message: str) -> None:
    if not condition:
        raise AcceptanceFailure(message)


def page_total(page: dict[str, Any]) -> int:
    metadata = page.get("page") or {}
    return int(metadata.get("totalElements", len(page.get("content", []))))


def check_health(client: ApiClient) -> dict[str, Any]:
    parsed = urlsplit(client.api_base_url)
    health_url = urlunsplit((parsed.scheme, parsed.netloc, "/actuator/health", "", ""))
    request = Request(health_url, headers={"Accept": "application/json"})
    try:
        with urlopen(request, timeout=30) as response:
            payload = json.loads(response.read())
    except (HTTPError, URLError, json.JSONDecodeError) as error:
        raise AcceptanceFailure(f"El health check de TEST no respondió correctamente: {error}") from error
    require(payload.get("status") == "UP", f"El backend no está UP: {payload}")
    return payload


def create_active_concept(client: ApiClient, label: str, run_id: str) -> dict[str, Any]:
    suffix = run_id.replace("-", "")[-12:].upper()
    concept = client.json(
        "POST",
        "/tax-concepts",
        {
            "code": f"QA_{label}_{suffix}",
            "name": f"QA {label} {suffix}",
            "description": "Dato efímero generado por la aceptación automatizada de AWS TEST",
            "type": "FEE",
            "originModule": "M5",
        },
        expected=(201,),
    )
    configuration = client.json(
        "POST",
        "/tax-configurations",
        {
            "taxConceptId": concept["id"],
            "calculationType": "FIXED",
            "rate": None,
            "fixedAmount": "100.00",
            "minimumAmount": None,
            "maximumAmount": None,
            "partialPaymentAllowed": True,
            "paymentPlanAllowed": True,
            "validFrom": (date.today() - timedelta(days=1)).isoformat(),
            "validUntil": None,
        },
        expected=(201,),
    )
    client.json("POST", f"/tax-configurations/{configuration['id']}/submit")
    approved = client.json("POST", f"/tax-configurations/{configuration['id']}/approve")
    require(approved.get("status") == "APPROVED", "La configuración de QA no quedó aprobada")
    return concept


def load_taxpayers(client: ApiClient) -> list[dict[str, Any]]:
    page = client.json("GET", "/taxpayers", query={"page": 0, "size": 200, "sort": "id,asc"})
    taxpayers = page.get("content", [])
    require(bool(taxpayers), "TEST no tiene contribuyentes. Debe cargarse al menos uno antes de ejecutar la aceptación.")
    return taxpayers


def run_pdf(
    client: ApiClient,
    taxpayers: list[dict[str, Any]],
    output_dir: Path,
    run_id: str,
) -> dict[str, Any]:
    concept = create_active_concept(client, "PDF", run_id)
    taxpayer = taxpayers[0]
    due_date = (date.today() + timedelta(days=30)).isoformat()
    liquidation = client.json(
        "POST",
        "/liquidations",
        {
            "taxpayerId": taxpayer["id"],
            "taxConceptId": concept["id"],
            "period": date.today().strftime("%Y-%m"),
            "taxableBase": "0.00",
            "dueDate": due_date,
        },
        expected=(201,),
    )
    debts = client.json(
        "GET",
        "/debts",
        query={
            "taxpayerId": taxpayer["id"],
            "taxConceptId": concept["id"],
            "page": 0,
            "size": 100,
        },
    ).get("content", [])
    debt = next((item for item in debts if item.get("liquidationId") == liquidation["id"]), None)
    require(debt is not None, "No se encontró la deuda creada por la liquidación de QA")

    bill = client.json(
        "POST",
        "/bills",
        {
            "taxpayerId": taxpayer["id"],
            "debtIds": [debt["id"]],
            "dueDate": (date.today() + timedelta(days=10)).isoformat(),
        },
        expected=(201,),
    )
    document = client.request("GET", f"/bills/{bill['id']}/document")
    content_type = document.headers.get("Content-Type", "")
    disposition = document.headers.get("Content-Disposition", "")
    require(content_type.startswith("application/pdf"), f"Content-Type inesperado: {content_type}")
    require("attachment" in disposition and bill["number"] in disposition, "Content-Disposition inválido")
    require(document.body.startswith(b"%PDF-"), "El documento no tiene una firma PDF válida")
    require(len(document.body) > 100, "El PDF generado está vacío o incompleto")
    printable = document.body.decode("latin-1", errors="ignore")
    require(bill["number"] in printable, "El PDF no contiene el número de boleta")
    require("100.00" in printable, "El PDF no contiene el total esperado")

    pdf_path = output_dir / f"boleta-{bill['number']}.pdf"
    pdf_path.write_bytes(document.body)
    return {
        "status": "passed",
        "billId": bill["id"],
        "billNumber": bill["number"],
        "taxpayerId": taxpayer["id"],
        "totalAmount": bill["totalAmount"],
        "pdfBytes": len(document.body),
        "artifact": str(pdf_path),
    }


def run_bulk(
    client: ApiClient,
    taxpayers: list[dict[str, Any]],
    bulk_size: int,
    max_duration_seconds: int,
    run_id: str,
) -> dict[str, Any]:
    require(bulk_size >= 2, "bulk-size debe ser al menos 2 para incluir un error controlado")
    concept = create_active_concept(client, "BULK", run_id)
    valid_taxpayers = taxpayers[: min(len(taxpayers), bulk_size - 1)]
    items = [
        {"taxpayerId": taxpayer["id"], "taxableBase": "0.00"}
        for taxpayer in valid_taxpayers
    ]
    invalid_count = bulk_size - len(items)
    for index in range(invalid_count):
        items.append({"taxpayerId": 9_000_000_000_000_000_000 + index, "taxableBase": "0.00"})

    before = client.json(
        "GET",
        "/liquidations",
        query={"conceptId": concept["id"], "page": 0, "size": 1},
    )
    require(page_total(before) == 0, "El concepto nuevo ya tenía liquidaciones antes del preview")

    started = time.monotonic()
    run = client.json(
        "POST",
        "/liquidation-runs",
        {
            "taxConceptId": concept["id"],
            "period": date.today().strftime("%Y-%m"),
            "dueDate": (date.today() + timedelta(days=30)).isoformat(),
            "items": items,
        },
        expected=(201,),
    )
    preview = client.json("POST", f"/liquidation-runs/{run['id']}/preview")
    elapsed_preview = time.monotonic() - started

    require(preview["run"]["totalItems"] == bulk_size, "El preview no procesó todos los ítems")
    require(preview["run"]["validItems"] == len(valid_taxpayers), "Cantidad de ítems válidos inesperada")
    require(preview["run"]["errorItems"] == invalid_count, "Cantidad de errores inesperada")
    still_empty = client.json(
        "GET",
        "/liquidations",
        query={"conceptId": concept["id"], "page": 0, "size": 1},
    )
    require(page_total(still_empty) == 0, "El preview creó liquidaciones antes de la aprobación")

    client.json("POST", f"/liquidation-runs/{run['id']}/submit")
    client.json(
        "POST",
        f"/liquidation-runs/{run['id']}/approve",
        {"observation": "Aceptación automatizada AWS TEST"},
    )
    executed = client.json("POST", f"/liquidation-runs/{run['id']}/execute")
    elapsed_total = time.monotonic() - started

    require(executed["status"] == "EXECUTED", "La corrida masiva no quedó EXECUTED")
    require(elapsed_total <= max_duration_seconds, (
        f"La corrida tardó {elapsed_total:.2f}s y superó el máximo de {max_duration_seconds}s"
    ))
    generated = client.json(
        "GET",
        "/liquidations",
        query={"conceptId": concept["id"], "page": 0, "size": 200},
    )
    require(page_total(generated) == len(valid_taxpayers), "No se generó exactamente una liquidación por ítem válido")

    second_execution = client.request(
        "POST",
        f"/liquidation-runs/{run['id']}/execute",
        expected=None,
    )
    require(second_execution.status >= 400, "Una segunda ejecución fue aceptada y podría duplicar liquidaciones")
    generated_after_retry = client.json(
        "GET",
        "/liquidations",
        query={"conceptId": concept["id"], "page": 0, "size": 200},
    )
    require(
        page_total(generated_after_retry) == len(valid_taxpayers),
        "La segunda ejecución alteró la cantidad de liquidaciones",
    )

    return {
        "status": "passed",
        "runId": run["id"],
        "requestedItems": bulk_size,
        "validItems": len(valid_taxpayers),
        "controlledErrors": invalid_count,
        "previewSeconds": round(elapsed_preview, 3),
        "totalSeconds": round(elapsed_total, 3),
        "duplicateExecutionHttpStatus": second_execution.status,
    }


def write_evidence(output_dir: Path, results: dict[str, Any], failure: str | None) -> None:
    payload = {"results": results, "failure": failure}
    (output_dir / "results.json").write_text(
        json.dumps(payload, indent=2, ensure_ascii=False),
        encoding="utf-8",
    )
    lines = ["# Aceptación AWS TEST", ""]
    if failure:
        lines.extend(["**Resultado:** FALLÓ", "", f"**Motivo:** {failure}", ""])
    else:
        lines.extend(["**Resultado:** EXITOSA", ""])
    for scenario, result in results.items():
        lines.extend([
            f"## {scenario.upper()}",
            "",
            json.dumps(result, indent=2, ensure_ascii=False),
            "",
        ])
    (output_dir / "summary.md").write_text("
".join(lines), encoding="utf-8")


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser()
    parser.add_argument("--api-base-url", required=True)
    parser.add_argument("--scenario", choices=("pdf", "bulk", "all"), default="all")
    parser.add_argument("--bulk-size", type=int, default=50)
    parser.add_argument("--max-duration-seconds", type=int, default=120)
    parser.add_argument("--output-dir", default="artifacts/qa-test")
    return parser.parse_args()


def main() -> int:
    args = parse_args()
    output_dir = Path(args.output_dir)
    output_dir.mkdir(parents=True, exist_ok=True)
    client = ApiClient(args.api_base_url, os.getenv("TEST_API_BEARER_TOKEN"))
    run_id = uuid.uuid4().hex
    results: dict[str, Any] = {}
    failure: str | None = None

    try:
        results["health"] = check_health(client)
        taxpayers = load_taxpayers(client)
        results["dataset"] = {"availableTaxpayers": len(taxpayers)}
        if args.scenario in ("pdf", "all"):
            results["pdf"] = run_pdf(client, taxpayers, output_dir, run_id)
        if args.scenario in ("bulk", "all"):
            results["bulk"] = run_bulk(
                client,
                taxpayers,
                args.bulk_size,
                args.max_duration_seconds,
                run_id,
            )
    except Exception as error:
        failure = str(error)
        print(f"ERROR: {failure}", file=sys.stderr)
    finally:
        write_evidence(output_dir, results, failure)

    return 1 if failure else 0


if __name__ == "__main__":
    raise SystemExit(main())
