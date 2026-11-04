# -*- coding: utf-8 -*-
import tempfile
import unittest
from unittest.mock import MagicMock
from pathlib import Path

from cute_tool.tool.docread.csv_processor import CSVProcessorTool
from cute_tool.tool.docread.pdf_reader import PDFReaderTool
from cute_tool.tool.docread._compat import ToolContext


class DocreadToolContractTest(unittest.TestCase):
    def test_query_should_sort_when_sort_column_is_valid(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "rows.csv"
            path.write_text("name,age\nA,1\nA,2\n", encoding="utf-8")
            result = CSVProcessorTool()._op_query(
                {
                    "file_path": str(path),
                    "filter_column": "name",
                    "filter_value": "A",
                    "sort_column": "age",
                    "sort_order": "desc",
                }
            )

        self.assertEqual(["2", "1"], [row["age"] for row in result["rows"]])

    def test_pdf_reader_run_should_use_execute_sync(self):
        tool = PDFReaderTool()
        progress = []
        tool.execute_sync = MagicMock(return_value={"page_count": 1})

        async def run_once():
            return await tool.run(
                {"operation": "metadata", "file_path": "demo.pdf"},
                ToolContext(session_id="req"),
                on_progress=progress.append,
            )

        import asyncio

        result = asyncio.run(run_once())

        self.assertTrue(result.success)
        self.assertEqual(1, len(progress))


if __name__ == "__main__":
    unittest.main()
