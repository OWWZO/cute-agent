# -*- coding: utf-8 -*-
import asyncio
import unittest
import tempfile
from pathlib import Path
from unittest.mock import patch

from cute_tool.tool.docread.paths import resolve_input_path, resolve_output_path


class DocreadPathSecurityTest(unittest.TestCase):
    def test_existing_absolute_path_outside_roots_should_be_rejected(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            outside = Path(temp_dir) / "outside.txt"
            outside.write_text("secret", encoding="utf-8")
            with self.assertRaises(FileNotFoundError):
                resolve_input_path(
                    str(outside), request_id="session-path-security"
                )

    def test_output_path_escape_should_be_rejected(self):
        with self.assertRaises(ValueError) as context:
            resolve_output_path(
                "../../etc/cron.d/x",
                request_id="session-path-security",
                workspace_root="C:\\tmp\\docread-workspace",
            )

        self.assertIn("escapes workspace root", str(context.exception))

    def test_absolute_output_path_should_be_rejected(self):
        with self.assertRaises(ValueError) as context:
            resolve_output_path(
                "C:\\tmp\\outside.txt",
                request_id="session-path-security",
                workspace_root="C:\\tmp\\docread-workspace",
            )

        self.assertIn("escapes workspace root", str(context.exception))

    def test_relative_output_path_should_stay_under_workspace(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            output = resolve_output_path(
                "nested/report.txt",
                request_id="session-path-security",
                workspace_root=temp_dir,
            )
            self.assertTrue(output.is_relative_to(Path(temp_dir).resolve()))

    def test_tool_runner_should_not_read_outside_workspace(self):
        from cute_tool.tool.docread.service import run_csv_processor

        with tempfile.TemporaryDirectory() as workspace_dir:
            with tempfile.TemporaryDirectory() as outside_dir:
                outside = Path(outside_dir) / "secret.csv"
                outside.write_text("secret\nvalue\n", encoding="utf-8")

                result = asyncio.run(
                    run_csv_processor(
                        "session-path-security",
                        {
                            "operation": "read",
                            "file_path": str(outside),
                            "workspace_root": workspace_dir,
                        },
                    )
                )

        self.assertFalse(result["success"])
        self.assertIn("File not found", result["message"])

    def test_tool_runner_should_return_failure_for_unexpected_path_error(self):
        from cute_tool.tool.docread.service import _run_sync_tool

        class OutputTool:
            path_params = ("file_path",)
            output_path_params = ("file_path",)

        with tempfile.TemporaryDirectory() as workspace_dir:
            with patch(
                "cute_tool.tool.docread.service.resolve_output_path",
                side_effect=PermissionError("workspace directory is not writable"),
            ):
                result = _run_sync_tool(
                    OutputTool(),
                    {
                        "operation": "write",
                        "file_path": "report.txt",
                        "workspace_root": workspace_dir,
                    },
                    "session-path-error",
                )

        self.assertFalse(result["success"])
        self.assertIn("not writable", result["message"])


if __name__ == "__main__":
    unittest.main()
