"""Regresiones del runner: no confundir errores técnicos o población inválida con éxito."""
import copy
import json
import unittest
from decimal import Decimal
from unittest.mock import Mock

from aws_test_acceptance import (
    AcceptanceFailure, HttpResponse, create_active_concept, expect_run_rejection, get_all_pages,
    load_taxpayers, run_bulk, validate_bulk_economics, validate_bulk_items,
)


class BulkAcceptanceTests(unittest.TestCase):
    def test_taxpayers_liquidations_and_debts_are_read_in_pages_of_at_most_100(self):
        client = Mock()

        def page(method, path, query):
            self.assertEqual(method, "GET")
            self.assertLessEqual(query["size"], 100)
            self.assertIn(path, ("/taxpayers", "/liquidations", "/debts"))
            start = query["page"] * query["size"]
            return {
                "content": [{"id": start + index + 1} for index in range(100)],
                "page": {"number": query["page"], "size": query["size"], "totalElements": 200},
            }

        client.json.side_effect = page
        taxpayers = load_taxpayers(client)
        self.assertEqual([item["id"] for item in taxpayers], list(range(1, 201)))

        for path in ("/liquidations", "/debts"):
            with self.subTest(path=path):
                items = get_all_pages(client, path, query={"taxConceptId": 9})
                self.assertEqual([item["id"] for item in items], list(range(1, 201)))

        calls = client.json.call_args_list
        self.assertEqual(len(calls), 6)
        for index in (0, 1):
            self.assertEqual(calls[index].kwargs["query"]["page"], index)
            self.assertEqual(calls[index].kwargs["query"]["size"], 100)
            self.assertEqual(calls[index].args[1], "/taxpayers")
        self.assertEqual([call.args[1] for call in calls[2:]], ["/liquidations", "/liquidations", "/debts", "/debts"])

    def test_pagination_rejects_size_above_api_maximum(self):
        with self.assertRaisesRegex(AcceptanceFailure, "entre 1 y 100"):
            get_all_pages(Mock(), "/debts", page_size=200)

    def test_pagination_rejects_incomplete_or_duplicate_pages(self):
        client = Mock()
        client.json.side_effect = [
            {"content": [{"id": 1}], "page": {"totalElements": 2}},
            {"content": [], "page": {"totalElements": 2}},
        ]
        with self.assertRaisesRegex(AcceptanceFailure, "página vacía"):
            get_all_pages(client, "/debts", page_size=1)

        client.json.side_effect = [
            {"content": [{"id": 1}], "page": {"totalElements": 2}},
            {"content": [{"id": 1}], "page": {"totalElements": 2}},
        ]
        with self.assertRaisesRegex(AcceptanceFailure, "duplicó elementos"):
            get_all_pages(client, "/debts", page_size=1)

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

    def test_configuration_is_approved_by_a_different_supervisor(self):
        client, approver = Mock(), Mock()
        client.json.side_effect = [{"id": 7}, {"id": 8}, {"status": "PENDING_APPROVAL"}]
        approver.json.return_value = {"status": "ACTIVE"}
        create_active_concept(client, approver, "QA", "run")
        self.assertNotIn("/tax-configurations/8/approve", [call.args[1] for call in client.json.call_args_list])
        approver.json.assert_called_once_with("POST", "/tax-configurations/8/approve")

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
