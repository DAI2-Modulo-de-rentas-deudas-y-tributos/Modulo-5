#!/usr/bin/env python3
"""Aceptación HTTP de AWS TEST; pypdf valida la estructura y el texto del PDF."""

from __future__ import annotations

import argparse
import json
import os
import sys
import time
import uuid
import hashlib
from decimal import Decimal
from email.message import Message
from io import BytesIO
from concurrent.futures import ThreadPoolExecutor
from threading import Barrier
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
    def __init__(self, api_base_url: str, bearer_token: str | None,
                 dev_roles: str = "RENTAS,SUPERVISOR,CASHIER", taxpayer_id: int | None = None) -> None:
        self.api_base_url = api_base_url.rstrip("/")
        # Amplify guarda el origen; también admitir el prefijo explícito del CLI.
        if not urlsplit(self.api_base_url).path:
            self.api_base_url += "/api/v1"
        self.bearer_token = bearer_token.strip() if bearer_token else None
        self.dev_roles = dev_roles
        self.taxpayer_id = taxpayer_id

    def _headers(self, has_body: bool) -> dict[str, str]:
        headers = {"Accept": "application/json, application/pdf"}
        if has_body:
            headers["Content-Type"] = "application/json"
        if self.bearer_token:
            headers["Authorization"] = f"Bearer {self.bearer_token}"
        else:
            headers["X-Dev-User"] = "qa-automation"
            headers["X-Dev-Roles"] = self.dev_roles
            if self.taxpayer_id is not None:
                headers["X-Dev-Taxpayer-Id"] = str(self.taxpayer_id)
        return headers

    def request(
        self,
        method: str,
        path: str,
        payload: Any | None = None,
        query: dict[str, Any] | None = None,
        expected: tuple[int, ...] | None = (200,),
        timeout: float = 60,
    ) -> HttpResponse:
        url = f"{self.api_base_url}/{path.lstrip('/')}"
        if query:
            url = f"{url}?{urlencode(query, doseq=True)}"
        encoded = None if payload is None else json.dumps(payload).encode("utf-8")
        request = Request(url, data=encoded, method=method, headers=self._headers(encoded is not None))
        try:
            with urlopen(request, timeout=timeout) as response:
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


def create_active_concept(client: ApiClient, label: str, run_id: str,
                          amount: str = "100.00") -> dict[str, Any]:
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
            "fixedAmount": amount,
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
    require(approved.get("status") == "ACTIVE", "La configuración de QA no quedó activa")
    return concept


def load_taxpayers(client: ApiClient) -> list[dict[str, Any]]:
    page = client.json("GET", "/taxpayers", query={"page": 0, "size": 200, "sort": "createdAt,asc"})
    taxpayers = page.get("content", [])
    require(bool(taxpayers), "TEST no tiene contribuyentes. Debe cargarse al menos uno antes de ejecutar la aceptación.")
    return taxpayers


def validate_pdf(document: HttpResponse, bill: dict[str, Any], debts: list[dict[str, Any]]) -> dict[str, Any]:
    from pypdf import PdfReader

    require(document.status == 200, f"La descarga devolvió HTTP {document.status}")
    content_type = document.headers.get("Content-Type", "").split(";", 1)[0].strip().lower()
    require(content_type == "application/pdf", f"Content-Type inesperado: {content_type}")
    headers = Message()
    headers["Content-Disposition"] = document.headers.get("Content-Disposition", "")
    require(headers.get_content_disposition() == "attachment", "El PDF no se entrega como adjunto")
    require(headers.get_filename() == f"{bill['number']}.pdf", "Nombre del adjunto incorrecto")
    require(document.body.startswith(b"%PDF-"), "Firma PDF inválida")
    require(document.body.rstrip().endswith(b"%%EOF"), "PDF truncado: falta EOF")
    try:
        reader = PdfReader(BytesIO(document.body), strict=True)
        require(not reader.is_encrypted, "El PDF no debe requerir contraseña")
        require(len(reader.pages) > 0, "El PDF no tiene páginas")
        text = "\n".join(page.extract_text() for page in reader.pages)
    except AcceptanceFailure:
        raise
    except Exception as error:
        raise AcceptanceFailure(f"El PDF no se puede leer: {error}") from error
    expected = [f"BOLETA {bill['number']}", f"Contribuyente: {bill['taxpayerId']}",
                f"Emision: {bill['issueDate']}", f"Vencimiento: {bill['dueDate']}",
                f"TOTAL: $ {Decimal(str(bill['totalAmount'])):.2f}"]
    expected.extend(f"Deuda {debt['debtId']}: $ {Decimal(str(debt['amountAtIssue'])):.2f}" for debt in debts)
    for value in expected:
        require(value in text, f"El PDF no contiene el dato esperado: {value}")
    require(sum(Decimal(str(debt['amountAtIssue'])) for debt in debts) == Decimal(str(bill['totalAmount'])),
            "El total no coincide con las deudas emitidas")
    for debt in debts:
        require(text.count(f"Deuda {debt['debtId']}:") == 1, "El PDF duplica una deuda")
    return {"pages": len(reader.pages), "pdfBytes": len(document.body),
            "sha256": hashlib.sha256(document.body).hexdigest()}


def run_pdf(
    client: ApiClient,
    taxpayers: list[dict[str, Any]],
    output_dir: Path,
    run_id: str,
) -> dict[str, Any]:
    taxpayer = taxpayers[0]
    due_date = (date.today() + timedelta(days=30)).isoformat()
    selected = []
    for index, amount in enumerate(("100.25", "250.50")):
        concept = create_active_concept(client, f"PDF{index}", run_id, amount)
        liquidation = client.json("POST", "/liquidations", {
            "taxpayerId": taxpayer["id"], "taxConceptId": concept["id"],
            "period": date.today().strftime("%Y-%m"), "taxableBase": "0.00", "dueDate": due_date,
        }, expected=(201,))
        debts = client.json("GET", "/debts", query={"taxpayerId": taxpayer["id"],
            "taxConceptId": concept["id"], "page": 0, "size": 100}).get("content", [])
        debt = next((item for item in debts if item.get("liquidationId") == liquidation["id"]), None)
        require(debt is not None, "No se encontró la deuda creada por QA")
        require(Decimal(str(debt["outstandingBalance"])) == Decimal(amount), "Importe de deuda incorrecto")
        selected.append(debt)

    evidence = []
    for count in (1, 2):
        bill = client.json("POST", "/bills", {
            "taxpayerId": taxpayer["id"], "debtIds": [debt["id"] for debt in selected[:count]],
            "dueDate": (date.today() + timedelta(days=10)).isoformat(),
        }, expected=(201,))
        expected_debts = [{"debtId": debt["id"], "amountAtIssue": debt["outstandingBalance"]}
                          for debt in selected[:count]]
        path = f"/bills/{bill['id']}/document"
        document = client.request("GET", path)
        # Conservar el archivo aun si el parser detecta un defecto.
        pdf_path = output_dir / f"boleta-{bill['id']}.pdf"
        pdf_path.write_bytes(document.body)
        metadata = validate_pdf(document, bill, expected_debts)
        repeated = client.request("GET", path)
        validate_pdf(repeated, bill, expected_debts)
        require(repeated.body == document.body, "La descarga repetida alteró el documento emitido")
        evidence.append({"billId": bill["id"], "billNumber": bill["number"], "totalAmount": bill["totalAmount"],
                         "debts": count, "artifact": str(pdf_path), "repeatDownload": "passed", **metadata})

    for debt in selected:
        after = client.json("GET", f"/debts/{debt['id']}")
        require(after["outstandingBalance"] == debt["outstandingBalance"] and after["status"] == debt["status"],
                "La emisión o descarga modificó una deuda")
    missing = client.request("GET", "/bills/9223372036854775807/document", expected=(404,))
    require(not missing.body.startswith(b"%PDF-"), "Una boleta inexistente devolvió un PDF")

    authorization: dict[str, Any] = {"status": "skipped", "reason": "Bearer: requiere identidades QA adicionales; cubierto en CI"}
    if not client.bearer_token:
        owner = ApiClient(client.api_base_url, None, "TAXPAYER", taxpayer["id"])
        validate_pdf(owner.request("GET", path), bill, expected_debts)
        for roles, owner_id in (("AUDITOR", None), ("TAXPAYER", None), ("TAXPAYER", 9223372036854775807)):
            denied = ApiClient(client.api_base_url, None, roles, owner_id).request("GET", path, expected=(403,))
            require(not denied.body.startswith(b"%PDF-"), "Una identidad sin permisos recibió el PDF")
        authorization = {"status": "passed", "mode": "dev-headers", "owner": "passed", "deniedCases": 3}
    return {"status": "passed", "documents": evidence, "debtUnchanged": "passed",
            "missingBill": "passed", "authorization": authorization, "visualReview": "pending"}


def run_bulk(
    client: ApiClient,
    taxpayers: list[dict[str, Any]],
    bulk_size: int,
    max_duration_seconds: int,
    run_id: str,
) -> dict[str, Any]:
    require(bulk_size in (10, 50, 200), "bulk-size debe ser 10, 50 o 200 contribuyentes válidos")
    require(len({taxpayer['id'] for taxpayer in taxpayers}) >= bulk_size,
            f"Se necesitan {bulk_size} contribuyentes distintos; hay {len(taxpayers)}. No se sustituye volumen real por errores.")
    concept = create_active_concept(client, "BULK", run_id)
    valid_taxpayers = list({taxpayer["id"]: taxpayer for taxpayer in taxpayers}.values())[:bulk_size]
    items = [
        {"taxpayerId": taxpayer["id"], "taxableBase": "0.00"}
        for taxpayer in valid_taxpayers
    ]
    invalid_count = 1
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

    require(preview["run"]["totalItems"] == bulk_size + invalid_count, "El preview no procesó todos los ítems")
    require(preview["run"]["validItems"] == len(valid_taxpayers), "Cantidad de ítems válidos inesperada")
    require(preview["run"]["errorItems"] == invalid_count, "Cantidad de errores inesperada")
    expected_amounts = validate_bulk_items(preview, {taxpayer["id"] for taxpayer in valid_taxpayers}, executed=False)
    repeat_preview = client.json("POST", f"/liquidation-runs/{run['id']}/preview")
    require(validate_bulk_items(repeat_preview, set(expected_amounts), executed=False) == expected_amounts,
            "Repetir el preview cambió los resultados")
    still_empty = client.json(
        "GET",
        "/liquidations",
        query={"conceptId": concept["id"], "page": 0, "size": 1},
    )
    require(page_total(still_empty) == 0, "El preview creó liquidaciones antes de la aprobación")
    require(page_total(client.json("GET", "/debts", query={"taxConceptId": concept["id"], "size": 1})) == 0,
            "El preview creó deudas")
    expect_run_rejection(client.request("POST", f"/liquidation-runs/{run['id']}/execute", expected=None))

    client.json("POST", f"/liquidation-runs/{run['id']}/submit")
    client.json(
        "POST",
        f"/liquidation-runs/{run['id']}/approve",
        {"observation": "Aceptación automatizada AWS TEST"},
    )
    # Dos solicitudes del mismo lote salen juntas: sólo una puede confirmar.
    gate = Barrier(2)
    def execute_once():
        gate.wait(timeout=10)
        return client.request("POST", f"/liquidation-runs/{run['id']}/execute", expected=None,
                              timeout=max_duration_seconds + 10)
    execution_started = time.monotonic()
    with ThreadPoolExecutor(max_workers=2) as pool:
        futures = [pool.submit(execute_once) for _ in range(2)]
        responses = [future.result(timeout=max_duration_seconds + 60) for future in futures]
    execution_seconds = time.monotonic() - execution_started
    require(sorted(response.status for response in responses) == [200, 422],
            f"Ejecución concurrente inesperada: {[response.status for response in responses]}")
    expect_run_rejection(next(response for response in responses if response.status != 200))
    executed = json.loads(next(response for response in responses if response.status == 200).body)
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
    detail = client.json("GET", f"/liquidation-runs/{run['id']}")
    require(detail["run"]["status"] == "EXECUTED", "La ejecución no quedó persistida")
    require(validate_bulk_items(detail, set(expected_amounts), executed=True) == expected_amounts,
            "La ejecución cambió los importes del preview")
    validate_bulk_economics(generated["content"], client.json("GET", "/debts",
        query={"taxConceptId": concept["id"], "size": 200})["content"], expected_amounts, detail["items"])

    second_execution = client.request(
        "POST",
        f"/liquidation-runs/{run['id']}/execute",
        expected=None,
    )
    expect_run_rejection(second_execution)
    generated_after_retry = client.json(
        "GET",
        "/liquidations",
        query={"conceptId": concept["id"], "page": 0, "size": 200},
    )
    require(
        page_total(generated_after_retry) == len(valid_taxpayers),
        "La segunda ejecución alteró la cantidad de liquidaciones",
    )
    validate_bulk_economics(generated_after_retry["content"], client.json("GET", "/debts",
        query={"taxConceptId": concept["id"], "size": 200})["content"], expected_amounts,
        client.json("GET", f"/liquidation-runs/{run['id']}")["items"])

    return {
        "status": "passed",
        "runId": run["id"],
        "requestedValidItems": bulk_size,
        "requestedItems": bulk_size + invalid_count,
        "validItems": len(valid_taxpayers),
        "controlledErrors": invalid_count,
        "previewSeconds": round(elapsed_preview, 3),
        "executionSeconds": round(execution_seconds, 3),
        "totalSeconds": round(elapsed_total, 3),
        "concurrentStatuses": sorted(response.status for response in responses),
        "estimatedTotalAmount": str(sum(expected_amounts.values())),
        "healthAfter": check_health(client)["status"],
        "duplicateExecutionHttpStatus": second_execution.status,
    }


def expect_run_rejection(response: HttpResponse) -> None:
    require(response.status == 422, f"Se esperaba rechazo de negocio HTTP 422, recibido {response.status}")
    try:
        code = json.loads(response.body).get("code")
    except (ValueError, AttributeError) as error:
        raise AcceptanceFailure("El rechazo no contiene un error JSON de negocio") from error
    require(code == "RUN_NOT_APPROVED", f"Error de negocio inesperado: {code}")


def validate_bulk_items(detail: dict[str, Any], taxpayers: set[int], executed: bool) -> dict[int, Decimal]:
    items = detail["items"]
    require(detail["run"]["totalItems"] == len(taxpayers) + 1
            and detail["run"]["validItems"] == len(taxpayers) and detail["run"]["errorItems"] == 1,
            "Los contadores persistidos del lote son inconsistentes")
    require(len(items) == len(taxpayers) + 1, "El detalle perdió o duplicó ítems")
    valid = [item for item in items if item["taxpayerId"] in taxpayers]
    errors = [item for item in items if item["taxpayerId"] not in taxpayers]
    require(len(valid) == len(taxpayers) and {item["taxpayerId"] for item in valid} == taxpayers,
            "La población válida cambió o tiene duplicados")
    require(len(errors) == 1 and errors[0]["status"] == "ERROR" and bool(errors[0].get("errorCode"))
            and bool(errors[0].get("errorMessage")) and errors[0].get("liquidationId") is None,
            "No se conservó el error parcial sin liquidación")
    for item in valid:
        require(item["status"] == ("LIQUIDATED" if executed else "VALID"), "Estado de ítem inesperado")
        require((item.get("liquidationId") is not None) == executed, "Referencia de liquidación incorrecta")
    amounts = {item["taxpayerId"]: Decimal(str(item["previewAmount"])) for item in valid}
    require(Decimal(str(detail["run"]["estimatedTotalAmount"])) == sum(amounts.values()), "Total del lote incorrecto")
    return amounts


def validate_bulk_economics(liquidations: list[dict[str, Any]], debts: list[dict[str, Any]],
                            expected: dict[int, Decimal], items: list[dict[str, Any]]) -> None:
    require(len(liquidations) == len(debts) == len(expected), "Cantidad de liquidaciones/deudas incorrecta")
    require({row["taxpayerId"] for row in liquidations} == set(expected), "Liquidaciones de una población incorrecta")
    require(len({row["id"] for row in liquidations}) == len(expected), "Liquidaciones duplicadas")
    links = {item["taxpayerId"]: item["liquidationId"] for item in items if item["status"] == "LIQUIDATED"}
    for liquidation in liquidations:
        owner = liquidation["taxpayerId"]
        require(links.get(owner) == liquidation["id"], "El ítem apunta a otra liquidación")
        require(Decimal(str(liquidation["finalAmount"])) == expected[owner], "Importe liquidado diferente del preview")
        matches = [debt for debt in debts if debt.get("liquidationId") == liquidation["id"]]
        require(len(matches) == 1, "Una liquidación no tiene exactamente una deuda")
        debt = matches[0]
        require(debt["taxpayerId"] == owner and debt["status"] == "PENDING"
                and Decimal(str(debt["outstandingBalance"])) == expected[owner], "Deuda o saldo inconsistentes")


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
    (output_dir / "summary.md").write_text("\n".join(lines), encoding="utf-8")


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
