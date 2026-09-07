"""Ejecutar después de Maven: lee PDFs reales obtenidos mediante MockMvc."""

import json
import tempfile
import unittest
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import patch

from aws_test_acceptance import AcceptanceFailure, ApiClient, HttpResponse, main, validate_pdf


class PdfAcceptanceTests(unittest.TestCase):
    @classmethod
    def setUpClass(cls):
        cls.directory = Path(__file__).resolve().parents[2] / "backend/target/pdf-evidence"
        if not (cls.directory / "bill-1.pdf").is_file():
            raise AssertionError("Primero ejecutar Maven con PdfDownloadTests para generar evidencia real")

    def fixture(self, count=1):
        manifest = json.loads((self.directory / f"bill-{count}.json").read_text(encoding="utf-8"))
        body = (self.directory / f"bill-{count}.pdf").read_bytes()
        return manifest, HttpResponse(200, {
            "Content-Type": "application/pdf",
            "Content-Disposition": f'attachment; filename="{manifest["bill"]["number"]}.pdf"',
        }, body)

    def test_real_documents_have_expected_pages_content_and_visible_text(self):
        from io import BytesIO
        from pypdf import PdfReader

        for count in (1, 3, 45):
            with self.subTest(debts=count):
                manifest, response = self.fixture(count)
                result = validate_pdf(response, manifest["bill"], manifest["debts"])
                self.assertEqual(result["pages"], manifest["expectedPages"])
                for page in PdfReader(BytesIO(response.body), strict=True).pages:
                    self.assertEqual(tuple(page.mediabox), (0, 0, 595, 842))
                    def visible(text, cm, tm, font, font_size):
                        if text.strip():
                            self.assertGreaterEqual(tm[5], 40, "Texto por debajo del margen imprimible")
                            self.assertLessEqual(tm[5] + font_size, 812, "Texto por encima de la página")
                    page.extract_text(visitor_text=visible)

    def test_rejects_html_truncation_and_broken_pdf_structure(self):
        manifest, response = self.fixture()
        for body in (b"<html>Error</html>", response.body[:-20], b"%PDF-1.4\ninvalid\n%%EOF"):
            with self.subTest(body=body[:20]), self.assertRaises(AcceptanceFailure):
                validate_pdf(HttpResponse(200, response.headers, body), manifest["bill"], manifest["debts"])

    def test_rejects_wrong_http_type_and_attachment_name(self):
        manifest, response = self.fixture()
        cases = [HttpResponse(500, response.headers, response.body)]
        for header, value in (("Content-Type", "text/html"), ("Content-Disposition", "inline"),
                              ("Content-Disposition", 'attachment; filename="wrong.pdf"')):
            cases.append(HttpResponse(200, {**response.headers, header: value}, response.body))
        for case in cases:
            with self.subTest(headers=case.headers), self.assertRaises(AcceptanceFailure):
                validate_pdf(case, manifest["bill"], manifest["debts"])

    def test_rejects_wrong_owner_dates_number_total_and_debt_amount(self):
        manifest, response = self.fixture()
        for key, value in (("number", "WRONG"), ("taxpayerId", -1), ("issueDate", "1900-01-01"),
                           ("dueDate", "1900-01-02"), ("totalAmount", "1.00")):
            with self.subTest(field=key), self.assertRaises(AcceptanceFailure):
                validate_pdf(response, {**manifest["bill"], key: value}, manifest["debts"])
        with self.assertRaises(AcceptanceFailure):
            validate_pdf(response, manifest["bill"], [{**manifest["debts"][0], "amountAtIssue": "1.00"}])

    def test_failure_returns_nonzero_and_preserves_readable_evidence(self):
        with tempfile.TemporaryDirectory() as directory:
            args = SimpleNamespace(output_dir=directory, api_base_url="https://example.invalid/api/v1", scenario="pdf")
            with patch("aws_test_acceptance.parse_args", return_value=args), \
                 patch("aws_test_acceptance.check_health", side_effect=AcceptanceFailure("QA failure")):
                self.assertEqual(main(), 1)
            evidence = json.loads((Path(directory) / "results.json").read_text(encoding="utf-8"))
            self.assertEqual(evidence["failure"], "QA failure")
            self.assertIn("FALLÓ", (Path(directory) / "summary.md").read_text(encoding="utf-8"))

    def test_bearer_authentication_does_not_send_dev_identity(self):
        headers = ApiClient("https://example.invalid/api/v1", "token", "TAXPAYER", 42)._headers(False)
        self.assertEqual(headers["Authorization"], "Bearer token")
        self.assertFalse(any(name.startswith("X-Dev-") for name in headers))

    def test_amplify_origin_and_explicit_api_prefix_resolve_to_same_api(self):
        for url in ("https://example.invalid", "https://example.invalid/", "https://example.invalid/api/v1/"):
            self.assertEqual(ApiClient(url, None).api_base_url, "https://example.invalid/api/v1")


if __name__ == "__main__":
    unittest.main()
