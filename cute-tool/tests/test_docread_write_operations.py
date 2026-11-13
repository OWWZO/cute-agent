# -*- coding: utf-8 -*-
import tempfile
import unittest
from pathlib import Path

from cute_tool.tool.docread.service import _resolve_existing, _run_sync_tool


class _OutputTool:
    path_params = ()
    output_path_params = ("file_path",)

    def execute_sync(self, params, context):
        path = Path(params["file_path"])
        path.write_text("created", encoding="utf-8")
        return {"file_path": str(path)}


class _ReadTool:
    path_params = ("file_path",)
    output_path_params = ()

    def execute_sync(self, params, context):
        raise AssertionError("read tool must not run when the input file is missing")


class DocreadWriteOperationTest(unittest.TestCase):
    def test_output_operations_should_create_missing_file_path(self):
        with tempfile.TemporaryDirectory() as workspace_dir:
            for operation in (
                "write",
                "append",
                "join",
                "build_table",
                "build_list",
            ):
                with self.subTest(operation=operation):
                    file_name = f"{operation}.txt"
                    result = _run_sync_tool(
                        _OutputTool(),
                        {
                            "operation": operation,
                            "file_path": file_name,
                            "workspace_root": workspace_dir,
                        },
                        "session-write-operation",
                    )

                    self.assertTrue(result["success"], result)
                    self.assertTrue(Path(workspace_dir, file_name).exists())

    def test_read_operation_should_still_reject_missing_file_path(self):
        with tempfile.TemporaryDirectory() as workspace_dir:
            with self.assertRaises(FileNotFoundError):
                _resolve_existing(
                    "missing.txt",
                    "session-write-operation",
                    workspace_dir,
                )

            result = _run_sync_tool(
                _ReadTool(),
                {
                    "operation": "read",
                    "file_path": "missing.txt",
                    "workspace_root": workspace_dir,
                },
                "session-write-operation",
            )

        self.assertFalse(result["success"])
        self.assertIn("File not found", result["message"])


if __name__ == "__main__":
    unittest.main()
