# -*- coding: utf-8 -*-
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from cute_tool.tool.docgen.service import resolve_output_path


class DocgenServicePathSecurityTest(unittest.TestCase):
    def test_request_id_should_not_escape_output_root(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            with patch(
                "cute_tool.tool.docgen.service.OUTPUT_DIR", Path(temp_dir)
            ):
                with self.assertRaises(ValueError):
                    resolve_output_path("..", "report.pdf", ".pdf")

    def test_default_extension_should_not_escape_output_root(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            with patch(
                "cute_tool.tool.docgen.service.OUTPUT_DIR", Path(temp_dir)
            ):
                with self.assertRaises(ValueError):
                    resolve_output_path("request-1", "report", "../../evil")


if __name__ == "__main__":
    unittest.main()
