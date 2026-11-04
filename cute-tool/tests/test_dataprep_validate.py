# -*- coding: utf-8 -*-
import unittest
from unittest.mock import AsyncMock, patch

import numpy as np
import pandas as pd

from cute_tool.tool.dataprep.data_validate import DataValidateTool
from cute_tool.tool.docread._compat import ToolContext


class DataValidateToolTest(unittest.TestCase):
    def test_should_report_all_unique_violations(self):
        rows = [{"id": "dup"} for _ in range(15)]
        df = pd.DataFrame(rows)
        schema = {"columns": {"id": {"type": "string", "unique": True}}}

        result = DataValidateTool().execute_sync(
            {"data": rows, "schema": schema}, ToolContext(session_id="req")
        )

        unique_error = next(
            error
            for error in result["errors"]
            if error["type"] == "unique_violation"
        )
        self.assertEqual(15, unique_error["total_violations"])
        self.assertEqual(15, len(unique_error["row_indices"]))
        self.assertEqual(15, result["summary"]["invalid_rows"])

    def test_should_accept_numpy_integer_column_as_integer(self):
        rows = [{"id": 1}, {"id": 2}]
        df = pd.DataFrame(rows)
        self.assertNotIsInstance(df.iloc[0]["id"], int)
        schema = {"columns": {"id": {"type": "integer"}}}

        result = DataValidateTool().execute_sync(
            {"data": rows, "schema": schema}, ToolContext(session_id="req")
        )

        self.assertTrue(result["valid"])
        self.assertEqual([], result["errors"])

    def test_should_accept_numpy_integer_scalar_as_integer(self):
        self.assertNotIsInstance(np.int64(1), int)
        self.assertIsInstance(np.int64(1), np.integer)
        self.assertNotIsInstance(np.int64(1), np.floating)
        # object 列保留 numpy 标量，直接覆盖 items() 产出 numpy.int64 的真实路径。
        df = pd.DataFrame(
            {"id": pd.Series([np.int64(1), np.int64(2)], dtype=object)}
        )

        errors = DataValidateTool()._check_type(df, "id", "integer", pd)

        self.assertEqual([], errors)


class DataprepExecutorTest(unittest.IsolatedAsyncioTestCase):
    async def test_run_data_validate_uses_blocking_executor(self):
        from cute_tool.tool.dataprep import service

        with patch("cute_tool.tool.dataprep.service.run_blocking", new=AsyncMock()) as run_blocking:
            run_blocking.return_value = {"success": True}

            await service.run_data_validate("req", {"schema": {}})

        run_blocking.assert_awaited_once()


if __name__ == "__main__":
    unittest.main()
