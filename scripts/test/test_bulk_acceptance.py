"""Regresiones del runner: no confundir errores técnicos o población inválida con éxito."""
import copy
import json
import os
import unittest
from decimal import Decimal
from datetime import date, datetime, timezone
from unittest.mock import Mock, patch

from aws_test_acceptance import (
    AcceptanceFailure, ApiClient, HttpResponse, approve_as_distinct_actor,
    expect_run_rejection, fiscal_today, get_all_pages, load_taxpayers,
    prepare_acceptance_actors, run_bulk, validate_bulk_economics, validate_bulk_items,
    verify_self_approval_paths,
)


class BulkAcceptanceTests(unittest.TestCase):
    def test_buenos_aires_fiscal_date_is_used_at_utc_day_boundary(self):
        from aws_test_acceptance import BUENOS_AIRES_TZ

        with patch("aws_test_acceptance.datetime") as clock:
            instant = datetime(2026, 11, 2, 0, 30, tzinfo=timezone.utc)
            clock.now.side_effect = lambda zone: instant.astimezone(zone)
            self.assertEqual(fiscal_today(), date(2026, 11, 1))
            clock.now.assert_called_once_with(BUENOS_AIRES_TZ)

    def test_bearer_acceptance_requires_two_distinct_role_tokens(self):
        client = ApiClient("https://example.invalid/api/v1", "general-token")
        with patch.dict(os.environ, {}, clear=True):
            with self.assertRaisesRegex(AcceptanceFailure, "TEST_API_RENTAS_BEARER_TOKEN"):
                prepare_acceptance_actors(client)
        with patch.dict(os.environ, {"TEST_API_RENTAS_BEARER_TOKEN": "same",
                                     "TEST_API_SUPERVISOR_BEARER_TOKEN": "same"}, clear=True):
            with self.assertRaisesRegex(AcceptanceFailure, "tokens distintos"):
                prepare_acceptance_actors(client)
        with patch.dict(os.environ, {"TEST_API_RENTAS_BEARER_TOKEN": "rentas-token",
                                     "TEST_API_SUPERVISOR_BEARER_TOKEN": "supervisor-token"}, clear=True):
            actors = prepare_acceptance_actors(client)
            self.assertEqual(actors.rentas.bearer_token, "rentas-token")
            self.assertEqual(actors.supervisor.bearer_token, "supervisor-token")
            self.assertIsNone(actors.demo_admin)

    def test_demo_acceptance_creates_rentas_and_supervisor_sessions_from_supervisor(self):
        client = Mock()
        client.bearer_token = None
        client.api_base_url = "http://localhost:8080/api/v1"
        client.json.return_value = {"authorities": ["RENTAS", "SUPERVISOR"]}
        rentas = ApiClient(client.api_base_url, None, "rentas-session")
        supervisor = ApiClient(client.api_base_url, None, "supervisor-session")
        with patch("aws_test_acceptance.demo_identity", side_effect=[rentas, supervisor]) as create:
            actors = prepare_acceptance_actors(client)
        self.assertIs(actors.rentas, rentas)
        self.assertIs(actors.supervisor, supervisor)
        self.assertIs(actors.demo_admin, client)
        self.assertEqual([call.args[1] for call in create.call_args_list], ["RENTAS", "SUPERVISOR"])

    def test_approval_requires_exact_self_approval_403_then_supervisor_success(self):
        requester = Mock()
        supervisor = Mock()
        requester.request.return_value = HttpResponse(
            403, {}, b'{"code":"SELF_APPROVAL_FORBIDDEN"}')
        supervisor.json.return_value = {"status": "ACTIVE"}
        result = approve_as_distinct_actor(
            requester, supervisor, "/tax-configurations/7/approve", expected_status="ACTIVE")
        self.assertEqual(result["status"], "ACTIVE")
        requester.request.assert_called_once_with(
            "POST", "/tax-configurations/7/approve", None, expected=None)
        supervisor.json.assert_called_once_with("POST", "/tax-configurations/7/approve", None)

    def test_approval_rejects_other_errors_or_unexpected_supervisor_status(self):
        for response in (HttpResponse(200, {}, b'{"status":"ACTIVE"}'),
                         HttpResponse(403, {}, b'{"code":"FORBIDDEN"}')):
            requester = Mock()
            requester.request.return_value = response
            with self.subTest(response=response), self.assertRaises(AcceptanceFailure):
                approve_as_distinct_actor(requester, Mock(), "/approve")

        requester = Mock()
        requester.request.return_value = HttpResponse(403, {}, b'{"code":"SELF_APPROVAL_FORBIDDEN"}')
        supervisor = Mock()
        supervisor.json.return_value = {"status": "PENDING_APPROVAL"}
        with self.assertRaisesRegex(AcceptanceFailure, "se esperaba ACTIVE"):
            approve_as_distinct_actor(requester, supervisor, "/approve", expected_status="ACTIVE")

    def test_http_self_approval_probes_both_workflows_then_approves_and_executes(self):
        requester = Mock()
        requester.demo_session = "requester"
        requester.bearer_token = None
        requester.api_base_url = "http://localhost/api/v1"
        requester.json.side_effect = [
            {"id": 1}, {"id": 2}, None, {"id": 3}, None, None, {"status": "EXECUTED"},
        ]
        requester.request.return_value = HttpResponse(403, {}, b'{"code":"SELF_APPROVAL_FORBIDDEN"}')
        supervisor = Mock()
        supervisor.json.side_effect = [{"status": "ACTIVE"}, {"status": "APPROVED"}]
        actors = Mock(demo_admin=requester, rentas=Mock(), supervisor=supervisor)

        result = verify_self_approval_paths(actors, {"id": 45}, "scenario-123456789012")

        self.assertEqual(result["taxConfiguration"], "SELF_APPROVAL_FORBIDDEN then ACTIVE")
        self.assertEqual(result["liquidationRun"], "SELF_APPROVAL_FORBIDDEN then APPROVED and EXECUTED")
        self.assertEqual(requester.request.call_count, 2)
        self.assertEqual(supervisor.json.call_count, 2)

    def test_bearer_self_approval_is_explicitly_skipped_without_dual_authority_identity(self):
        actors = Mock(demo_admin=None, rentas=ApiClient("https://example.invalid", "rentas"),
                      supervisor=ApiClient("https://example.invalid", "supervisor"))
        with patch.dict(os.environ, {}, clear=True):
            result = verify_self_approval_paths(actors, {"id": 45}, "scenario")
        self.assertEqual(result["status"], "skipped")
        self.assertIn("TEST_API_SELF_APPROVAL_BEARER_TOKEN", result["reason"])

    def test_bearer_self_approval_never_reuses_approver_identity(self):
        actors = Mock(demo_admin=None, rentas=ApiClient("https://example.invalid", "rentas"),
                      supervisor=ApiClient("https://example.invalid", "same"))
        with patch.dict(os.environ, {"TEST_API_SELF_APPROVAL_BEARER_TOKEN": "same"}, clear=True):
            with self.assertRaisesRegex(AcceptanceFailure, "distinta del supervisor"):
                verify_self_approval_paths(actors, {"id": 45}, "scenario")

    def test_access_denied_is_not_reported_as_self_approval(self):
        with self.assertRaisesRegex(AcceptanceFailure, "antes de evaluar SELF_APPROVAL_FORBIDDEN"):
            from aws_test_acceptance import expect_self_approval_rejection
            expect_self_approval_rejection(HttpResponse(403, {}, b'{"code":"ACCESS_DENIED"}'))

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
                run_bulk(client, Mock(), taxpayers, 50, 120, "qa")
        client.json.assert_not_called()
        client.request.assert_not_called()

    def test_only_the_expected_business_rejection_counts_as_idempotency(self):
        correct = json.dumps({"code": "RUN_NOT_APPROVED"}).encode()
        expect_run_rejection(HttpResponse(409, {}, correct))
        for status in (200, 401, 403, 404, 422, 500, 502, 503):
            with self.subTest(status=status), self.assertRaises(AcceptanceFailure):
                expect_run_rejection(HttpResponse(status, {}, correct))
        for body in (b'{}', b'{"code":"OTHER_ERROR"}', b'<html>Error</html>'):
            with self.assertRaises(AcceptanceFailure):
                expect_run_rejection(HttpResponse(409, {}, body))

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
