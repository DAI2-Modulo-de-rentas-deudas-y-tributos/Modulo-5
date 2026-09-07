"""Regresiones del runner: no confundir errores técnicos o población inválida con éxito."""
import copy
import json
import unittest
from decimal import Decimal
from unittest.mock import Mock

from aws_test_acceptance import (
    AcceptanceFailure, HttpResponse, expect_run_rejection,
    run_bulk, validate_bulk_economics, validate_bulk_items,
)


class BulkAcceptanceTests(unittest.TestCase):
    def detail(self, executed=False):
        return {"run": {"estimatedTotalAmount": "300.75", "totalItems": 3, "validItems": 2, "errorItems": 1},
                "items": [
                    {"taxpayerId": 1, "status": "LIQUIDATED" if executed else "VALID",
                     "previewAmount": "100.25", "liquidationId": 11 if executed else None},
                    {"taxpayerId": 2, "status": "LIQUIDATED" if executed else "VALID",
                     "previewAmount": "200.50", "liquidationId": 12 if executed else None},
                    {"taxpayerId": 999, "status": "ERROR", "liquidationId": None,
                     "errorCode": "NOT_FOUND", "errorMessage": "Contribuyente inexistente"}]}

    def economy(self):
        liquidations = [{"id": 11, "taxpayerId": 1, "finalAmount": "100.25"},
                        {"id": 12, "taxpayerId": 2, "finalAmount": "200.50"}]
        debts = [{"liquidationId": row["id"], "taxpayerId": row["taxpayerId"],
                  "status": "PENDING", "outstandingBalance": row["finalAmount"]} for row in liquidations]
        return liquidations, debts

    def test_insufficient_real_population_fails_before_creating_data(self):
        client = Mock()
        for taxpayers in ([{"id": 1}], [{"id": 1}] * 200):
            with self.assertRaisesRegex(AcceptanceFailure, "contribuyentes distintos"):
                run_bulk(client, taxpayers, 50, 120, "qa")
        client.json.assert_not_called()
        client.request.assert_not_called()

    def test_only_the_expected_business_rejection_counts_as_idempotency(self):
        correct = json.dumps({"code": "RUN_NOT_APPROVED"}).encode()
        expect_run_rejection(HttpResponse(422, {}, correct))
        for status in (200, 401, 403, 404, 409, 500, 502, 503):
            with self.subTest(status=status), self.assertRaises(AcceptanceFailure):
                expect_run_rejection(HttpResponse(status, {}, correct))
        for body in (b'{}', b'{"code":"OTHER_ERROR"}', b'<html>Error</html>'):
            with self.assertRaises(AcceptanceFailure):
                expect_run_rejection(HttpResponse(422, {}, body))

    def test_preview_and_execution_preserve_decimal_amounts_and_partial_error(self):
        expected = {1: Decimal("100.25"), 2: Decimal("200.50")}
        for executed in (False, True):
            self.assertEqual(validate_bulk_items(self.detail(executed), {1, 2}, executed), expected)
        liquidations, debts = self.economy()
        validate_bulk_economics(liquidations, debts, expected, self.detail(True)["items"])

    def test_lost_or_duplicate_items_wrong_totals_and_false_errors_fail(self):
        for change in ("lost", "duplicate", "total", "counter", "error", "preview_write"):
            detail = self.detail()
            if change == "lost": detail["items"].pop()
            if change == "duplicate": detail["items"][1] = copy.deepcopy(detail["items"][0])
            if change == "total": detail["run"]["estimatedTotalAmount"] = "301"
            if change == "counter": detail["run"]["validItems"] = 0
            if change == "error": detail["items"][2]["errorCode"] = None
            if change == "preview_write": detail["items"][0]["liquidationId"] = 11
            with self.subTest(change=change), self.assertRaises(AcceptanceFailure):
                validate_bulk_items(detail, {1, 2}, False)

    def test_missing_debts_wrong_owners_amounts_and_links_fail(self):
        expected = {1: Decimal("100.25"), 2: Decimal("200.50")}
        for change in ("missing", "owner", "amount", "debt_amount", "duplicate", "link"):
            liquidations, debts = self.economy()
            items = self.detail(True)["items"]
            if change == "missing": debts.pop()
            if change == "owner": debts[0]["taxpayerId"] = 2
            if change == "amount": liquidations[0]["finalAmount"] = "1"
            if change == "debt_amount": debts[0]["outstandingBalance"] = "1"
            if change == "duplicate": liquidations[1] = copy.deepcopy(liquidations[0])
            if change == "link": items[0]["liquidationId"] = 999
            with self.subTest(change=change), self.assertRaises(AcceptanceFailure):
                validate_bulk_economics(liquidations, debts, expected, items)


if __name__ == "__main__":
    unittest.main()
