# -*- coding: utf-8 -*-
import unittest
from unittest.mock import patch

from fastapi import FastAPI
from fastapi.testclient import TestClient

from cute_tool.util.middleware_util import UnknownException


class UnknownExceptionTest(unittest.TestCase):
    def test_should_return_unified_json_without_internal_message(self):
        app = FastAPI()
        app.add_middleware(UnknownException)

        @app.get("/boom")
        def boom():
            raise RuntimeError("internal secret")

        client = TestClient(app, raise_server_exceptions=False)
        with patch(
            "cute_tool.util.middleware_util.logger.exception"
        ) as log_exception:
            response = client.get("/boom")

        self.assertEqual(500, response.status_code)
        body = response.json()
        self.assertEqual(500, body["code"])
        self.assertEqual("Internal server error", body["message"])
        self.assertIn("requestId", body)
        self.assertNotIn("internal secret", response.text)
        log_exception.assert_called_once()


if __name__ == "__main__":
    unittest.main()
