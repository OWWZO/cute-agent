# -*- coding: utf-8 -*-
import unittest
from unittest.mock import AsyncMock, patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

from cute_tool.api.sandbox_routes import router
from cute_tool.tool.direct_code_execution import WorkspaceRootNotAllowedError


class SandboxRoutesTest(unittest.TestCase):
    def setUp(self):
        app = FastAPI()
        app.include_router(router, prefix="/v1/tool")
        self.client = TestClient(app)

    def _post_code_execution(self, request_id: str):
        return self.client.post(
            "/v1/tool/code_execution",
            json={
                "requestId": request_id,
                "source": "answer = 1",
            },
        )

    def test_should_return_forbidden_for_disallowed_workspace(self):
        with patch(
            "cute_tool.tool.direct_code_execution.execute_code",
            new=AsyncMock(
                side_effect=WorkspaceRootNotAllowedError(
                    "workspaceRoot is outside allowed workspace roots"
                )
            ),
        ):
            response = self._post_code_execution(
                "req-code-workspace-denied"
            )

        self.assertEqual(403, response.status_code)
        body = response.json()
        self.assertEqual(403, body["code"])
        self.assertEqual(
            "WORKSPACE_ROOT_NOT_ALLOWED", body["errorCode"]
        )
        self.assertEqual(
            "req-code-workspace-denied", body["requestId"]
        )

    def test_should_return_bad_request_for_invalid_code_execution_request(self):
        with patch(
            "cute_tool.tool.direct_code_execution.execute_code",
            new=AsyncMock(side_effect=ValueError("invalid workspaceFile")),
        ):
            response = self._post_code_execution(
                "req-code-invalid-request"
            )

        self.assertEqual(400, response.status_code)
        body = response.json()
        self.assertEqual(
            "INVALID_CODE_EXECUTION_REQUEST", body["errorCode"]
        )
        self.assertEqual("req-code-invalid-request", body["requestId"])

    def test_should_hide_unexpected_error_details(self):
        with patch(
            "cute_tool.tool.direct_code_execution.execute_code",
            new=AsyncMock(side_effect=RuntimeError("internal secret")),
        ):
            response = self._post_code_execution(
                "req-code-internal-error"
            )

        self.assertEqual(500, response.status_code)
        body = response.json()
        self.assertEqual("CODE_EXECUTION_FAILED", body["errorCode"])
        self.assertNotIn("internal secret", body["message"])


if __name__ == "__main__":
    unittest.main()
