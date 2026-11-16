import os
import threading
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

from cute_tool.model.protocal import CodeExecutionRequest
from cute_tool.tool.direct_code_execution import (
    WorkspaceRootNotAllowedError,
    _workspace_path,
    execute_code,
)
from cute_tool.tool.user_sandbox_manager import SessionBusyError


class DirectCodeExecutionTest(unittest.IsolatedAsyncioTestCase):
    async def test_should_run_sandbox_execution_off_the_event_loop(self):
        observed_threads = []

        class FakeExecutor:
            def __init__(self, *args, **kwargs):
                pass

            def execute(self, *args, **kwargs):
                observed_threads.append(threading.current_thread().name)
                return type(
                    "Result",
                    (),
                    {
                        "stdout": "worker",
                        "stderr": "",
                        "result": None,
                        "duration_ms": 1,
                        "returncode": 0,
                        "stdout_truncated": False,
                        "stderr_truncated": False,
                    },
                )()

            def produced_files(self):
                return []

            def close(self):
                pass

        with (
            tempfile.TemporaryDirectory() as workspace,
            patch.dict(
                "os.environ", {"CODE_EXECUTION_WORKSPACE_ROOT": workspace}, clear=False
            ),
            patch(
                "cute_tool.tool.direct_code_execution.PythonSandboxExecutor",
                FakeExecutor,
            ),
        ):
            result = await execute_code(
                CodeExecutionRequest(requestId="worker-check", source="print('ok')")
            )

        self.assertEqual("ok", result["status"])
        self.assertTrue(observed_threads)
        self.assertTrue(observed_threads[0].startswith("cute-tool-blocking"))

    async def test_should_return_stdout_and_uploaded_produced_file(self):
        with (
            tempfile.TemporaryDirectory() as workspace,
            patch.dict(
                "os.environ", {"CODE_EXECUTION_WORKSPACE_ROOT": workspace}, clear=False
            ),
            patch(
                "cute_tool.tool.direct_code_execution.upload_file_by_path",
                new=AsyncMock(
                    return_value={
                        "fileName": "result.txt",
                        "ossUrl": "download/result.txt",
                        "domainUrl": "preview/result.txt",
                        "fileSize": 2,
                    }
                ),
            ) as upload,
        ):
            result = await execute_code(
                CodeExecutionRequest(
                    requestId="session-direct-execution",
                    source=(
                        "from pathlib import Path\n"
                        "Path('result.txt').write_text('ok', encoding='utf-8')\n"
                        "print(inputs_value * 2)"
                    ),
                    inputs={"inputs_value": 21},
                )
            )

        self.assertEqual("ok", result["status"])
        self.assertIn("42", result["stdout"])
        self.assertEqual(
            ["result.txt"], [item["name"] for item in result["producedFiles"]]
        )
        self.assertEqual("result.txt", result["fileInfo"][0]["fileName"])
        upload.assert_awaited_once()

    async def test_should_preserve_stdout_and_files_when_source_fails(self):
        with (
            tempfile.TemporaryDirectory() as workspace,
            patch.dict(
                "os.environ", {"CODE_EXECUTION_WORKSPACE_ROOT": workspace}, clear=False
            ),
            patch(
                "cute_tool.tool.direct_code_execution.upload_file_by_path",
                new=AsyncMock(return_value={"fileName": "partial.txt"}),
            ),
        ):
            result = await execute_code(
                CodeExecutionRequest(
                    requestId="session-direct-error",
                    source=(
                        "from pathlib import Path\n"
                        "print('before failure')\n"
                        "Path('partial.txt').write_text('x')\n"
                        "raise RuntimeError('boom')"
                    ),
                )
            )

        self.assertEqual("error", result["status"])
        self.assertIn("before failure", result["stdout"])
        self.assertEqual(
            ["partial.txt"], [item["name"] for item in result["producedFiles"]]
        )

    async def test_should_keep_execution_result_when_upload_fails(self):
        with (
            tempfile.TemporaryDirectory() as workspace,
            patch.dict(
                os.environ,
                {
                    "CODE_EXECUTION_WORKSPACE_ROOT": workspace,
                    "CODE_SANDBOX_BACKEND": "local",
                },
                clear=False,
            ),
            patch(
                "cute_tool.tool.direct_code_execution.upload_file_by_path",
                new=AsyncMock(
                    side_effect=TimeoutError(
                        "binary file upload timed out after 60s"
                    )
                ),
            ),
        ):
            result = await execute_code(
                CodeExecutionRequest(
                    requestId="session-upload-timeout",
                    source=(
                        "from pathlib import Path\n"
                        "Path('partial.txt').write_text('x', encoding='utf-8')\n"
                        "print('execution-ok')\n"
                    ),
                )
            )

        self.assertEqual("ok", result["status"])
        self.assertIn("execution-ok", result["stdout"])
        self.assertEqual([], result["fileInfo"])
        self.assertIn("产物上传失败: partial.txt", result["stderr"])
        self.assertIn(
            "binary file upload timed out after 60s",
            result["stderr"],
        )
        self.assertTrue(result["warnings"])

    async def test_should_return_session_busy_as_execution_error(self):
        class BusyExecutor:
            def __init__(self, *args, **kwargs):
                pass

            def execute(self, *args, **kwargs):
                raise SessionBusyError("同会话正在执行，请稍后重试")

            def produced_files(self):
                return []

            def close(self):
                pass

        with (
            tempfile.TemporaryDirectory() as workspace,
            patch.dict(
                os.environ, {"CODE_EXECUTION_WORKSPACE_ROOT": workspace}, clear=False
            ),
            patch(
                "cute_tool.tool.direct_code_execution.PythonSandboxExecutor",
                BusyExecutor,
            ),
        ):
            result = await execute_code(
                CodeExecutionRequest(
                    requestId="session-busy",
                    source="print('never runs')",
                )
            )

        self.assertEqual("error", result["status"])
        self.assertIn("同会话正在执行", result["error"])

    async def test_should_upload_relative_cwd_file_and_workspace_root_file(self):
        uploads = AsyncMock(
            side_effect=[
                {
                    "fileName": "chart.png",
                    "ossUrl": "d/chart.png",
                    "domainUrl": "p/chart.png",
                    "fileSize": 3,
                },
                {
                    "fileName": "notes.txt",
                    "ossUrl": "d/notes.txt",
                    "domainUrl": "p/notes.txt",
                    "fileSize": 2,
                },
            ]
        )
        with (
            tempfile.TemporaryDirectory() as workspace,
            patch.dict(
                "os.environ", {"CODE_EXECUTION_WORKSPACE_ROOT": workspace}, clear=False
            ),
            patch(
                "cute_tool.tool.direct_code_execution.upload_file_by_path",
                new=uploads,
            ),
        ):
            result = await execute_code(
                CodeExecutionRequest(
                    requestId="session-cwd-root-harvest",
                    permissionProfile="workspace",
                    source=(
                        "from pathlib import Path\n"
                        "Path('chart.png').write_bytes(b'png')\n"
                        "Path(workspace_root).joinpath('notes.txt').write_text('ok', encoding='utf-8')\n"
                    ),
                )
            )

        self.assertEqual("ok", result["status"])
        self.assertEqual(
            {"chart.png", "notes.txt"},
            {item["name"] for item in result["producedFiles"]},
        )
        self.assertEqual(2, uploads.await_count)

    async def test_should_upload_nested_produced_file_with_relative_path(self):
        upload = AsyncMock(
            return_value={
                "fileName": "desktop.png",
                "relativePath": "test-page/screens/desktop.png",
                "ossUrl": "d/desktop.png",
                "domainUrl": "p/desktop.png",
                "fileSize": 3,
            }
        )
        with (
            tempfile.TemporaryDirectory() as workspace,
            patch.dict(
                "os.environ", {"CODE_EXECUTION_WORKSPACE_ROOT": workspace}, clear=False
            ),
            patch(
                "cute_tool.tool.direct_code_execution.upload_file_by_path",
                new=upload,
            ),
        ):
            result = await execute_code(
                CodeExecutionRequest(
                    requestId="session-nested-produced",
                    permissionProfile="workspace",
                    source=(
                        "from pathlib import Path\n"
                        "target = Path('test-page/screens/desktop.png')\n"
                        "target.parent.mkdir(parents=True, exist_ok=True)\n"
                        "target.write_bytes(b'png')\n"
                    ),
                )
            )

        self.assertEqual("ok", result["status"])
        self.assertEqual(
            "test-page/screens/desktop.png",
            upload.await_args.kwargs["relative_path"],
        )
        self.assertEqual(
            "test-page/screens/desktop.png",
            result["fileInfo"][0]["relativePath"],
        )

    async def test_should_use_skilloutput_session_workspace_by_default(self):
        with tempfile.TemporaryDirectory() as skill_output:
            env = {
                key: value
                for key, value in os.environ.items()
                if key != "CODE_EXECUTION_WORKSPACE_ROOT"
            }
            env["SKILL_OUTPUT_ROOT"] = skill_output
            with (
                patch.dict("os.environ", env, clear=True),
                patch(
                    "cute_tool.tool.direct_code_execution.upload_file_by_path",
                    new=AsyncMock(return_value={"fileName": "out.txt"}),
                ),
            ):
                result = await execute_code(
                    CodeExecutionRequest(
                        requestId="session-skill-align",
                        source=(
                            "from pathlib import Path\n"
                            "Path('out.txt').write_text('ok', encoding='utf-8')\n"
                        ),
                    )
                )
                self.assertEqual("ok", result["status"])
                expected = Path(skill_output) / "session-skill-align"
                self.assertEqual(
                    str(expected.resolve()), str(Path(result["workspace"]).resolve())
                )
                self.assertTrue((expected / "out.txt").is_file())

    async def test_should_honor_explicit_workspace_root(self):
        with tempfile.TemporaryDirectory() as session_root:
            with (
                patch.dict(
                    "os.environ",
                    {"CODE_EXECUTION_WORKSPACE_ROOT": session_root},
                    clear=False,
                ),
                patch(
                    "cute_tool.tool.direct_code_execution.upload_file_by_path",
                    new=AsyncMock(return_value={"fileName": "a.txt"}),
                ),
            ):
                result = await execute_code(
                    CodeExecutionRequest(
                        requestId="ignored-for-path",
                        workspaceRoot=session_root,
                        source=(
                            "from pathlib import Path\n"
                            "Path('a.txt').write_text('x', encoding='utf-8')\n"
                        ),
                    )
                )
            self.assertEqual("ok", result["status"])
            self.assertEqual(
                str(Path(session_root).resolve()),
                str(Path(result["workspace"]).resolve()),
            )
            self.assertTrue((Path(session_root) / "a.txt").is_file())

    async def test_should_read_nested_workspace_file_with_relative_path(self):
        with tempfile.TemporaryDirectory() as session_root:
            with patch.dict(
                "os.environ",
                {"CODE_EXECUTION_WORKSPACE_ROOT": session_root},
                clear=False,
            ):
                report = Path(session_root) / "chinagt-shanghai-fire-report"
                report.mkdir()
                (report / "index.html").write_text(
                    "<html>ok</html>\n", encoding="utf-8"
                )
                result = await execute_code(
                    CodeExecutionRequest(
                        requestId="session-workspace-read",
                        workspaceRoot=session_root,
                        source=(
                            "from pathlib import Path\n"
                            "print(Path('chinagt-shanghai-fire-report/index.html').read_text(encoding='utf-8').strip())\n"
                        ),
                    )
                )
                self.assertEqual("ok", result["status"], result)
                self.assertIn("<html>ok</html>", result["stdout"])

    async def test_should_reject_workspace_root_outside_allowed_roots(self):
        captured = {}

        class FakeExecutor:
            def __init__(self, *args, **kwargs):
                captured["initialized"] = True

            def execute(self, *args, **kwargs):
                return type(
                    "Result",
                    (),
                    {
                        "stdout": "",
                        "stderr": "",
                        "result": None,
                        "duration_ms": 1,
                        "returncode": 0,
                        "stdout_truncated": False,
                        "stderr_truncated": False,
                    },
                )()

            def produced_files(self):
                return []

            def close(self):
                pass

        with (
            tempfile.TemporaryDirectory() as allowed_root,
            tempfile.TemporaryDirectory() as outside_root,
            patch.dict(
                "os.environ",
                {"CODE_EXECUTION_WORKSPACE_ROOT": allowed_root},
                clear=False,
            ),
            patch(
                "cute_tool.tool.direct_code_execution.PythonSandboxExecutor",
                FakeExecutor,
            ),
        ):
            with self.assertRaises(WorkspaceRootNotAllowedError) as raised:
                await execute_code(
                    CodeExecutionRequest(
                        requestId="unsafe-root",
                        workspaceRoot=outside_root,
                        source="answer = 1",
                    )
                )
            self.assertEqual(403, raised.exception.status_code)
            self.assertEqual(
                "WORKSPACE_ROOT_NOT_ALLOWED",
                raised.exception.error_code,
            )
            self.assertNotIn("initialized", captured)
            self.assertFalse((Path(outside_root) / "input").exists())

    async def test_should_not_grant_read_access_to_file_outside_workspace(self):
        captured = {}

        class FakeExecutor:
            def __init__(self, policy, *args, **kwargs):
                captured["policy"] = policy

            def execute(self, *args, **kwargs):
                return type(
                    "Result",
                    (),
                    {
                        "stdout": "",
                        "stderr": "",
                        "result": None,
                        "duration_ms": 1,
                        "returncode": 0,
                        "stdout_truncated": False,
                        "stderr_truncated": False,
                    },
                )()

            def produced_files(self):
                return []

            def close(self):
                pass

        with (
            tempfile.TemporaryDirectory() as workspace_root,
            tempfile.TemporaryDirectory() as outside_root,
            patch.dict(
                "os.environ",
                {"CODE_EXECUTION_WORKSPACE_ROOT": workspace_root},
                clear=False,
            ),
            patch(
                "cute_tool.tool.direct_code_execution.download_all_files_in_path",
                new=AsyncMock(
                    return_value=[
                        {
                            "file_name": "secret.txt",
                            "file_path": str(Path(outside_root) / "secret.txt"),
                        }
                    ]
                ),
            ),
            patch(
                "cute_tool.tool.direct_code_execution.PythonSandboxExecutor",
                FakeExecutor,
            ),
        ):
            result = await execute_code(
                CodeExecutionRequest(
                    requestId="outside-input",
                    fileNames=[str(Path(outside_root) / "secret.txt")],
                    source="answer = 1",
                )
            )

        self.assertEqual("ok", result["status"])
        self.assertNotIn(
            str(Path(outside_root) / "secret.txt"),
            captured["policy"].allowed_read_paths,
        )

    async def test_should_allow_explicit_workspace_root_inside_allowed_root(self):
        with (
            tempfile.TemporaryDirectory() as allowed_root,
            patch.dict(
                "os.environ",
                {"CODE_EXECUTION_WORKSPACE_ROOT": allowed_root},
                clear=False,
            ),
            patch(
                "cute_tool.tool.direct_code_execution.upload_file_by_path",
                new=AsyncMock(return_value={"fileName": "a.txt"}),
            ),
        ):
            session_root = Path(allowed_root) / "session-1"
            result = await execute_code(
                CodeExecutionRequest(
                    requestId="allowed-root",
                    workspaceRoot=str(session_root),
                    source=(
                        "from pathlib import Path\n"
                        "Path('a.txt').write_text('x', encoding='utf-8')\n"
                    ),
                )
            )
            self.assertEqual("ok", result["status"])
            self.assertTrue((session_root / "a.txt").is_file())

    async def test_should_accept_workspace_from_multi_root_config(self):
        with (
            tempfile.TemporaryDirectory() as allowed_root,
            patch.dict(
                "os.environ",
                {"CODE_EXECUTION_WORKSPACE_ROOTS": allowed_root},
                clear=False,
            ),
        ):
            session_root = Path(allowed_root) / "case-1"
            selected = _workspace_path(
                CodeExecutionRequest(
                    requestId="multi-root",
                    workspaceRoot=str(session_root),
                    source="answer = 1",
                )
            )

            self.assertEqual(session_root.resolve(), selected)


if __name__ == "__main__":
    unittest.main()
