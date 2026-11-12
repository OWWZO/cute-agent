# -*- coding: utf-8 -*-
import tempfile
import unittest
from pathlib import Path
from unittest.mock import AsyncMock, patch

from cute_tool.tool.code_interpreter_policy import CodeExecutionPermissionError
from cute_tool.tool import code_interpreter as code_interpreter_module


class FakeAgent:
    def run(self, task: str, stream: bool = True, max_steps: int = 10):
        return task

    def close_sandbox(self):
        return None


class CodeInterpreterInputSandboxTest(unittest.IsolatedAsyncioTestCase):
    async def _consume_agent(self, **kwargs):
        return [
            item async for item in code_interpreter_module.code_interpreter_agent(**kwargs)
        ]

    async def test_should_reject_absolute_input_outside_workspace(self):
        with tempfile.TemporaryDirectory() as root:
            workspace = Path(root) / "workspace"
            workspace.mkdir()
            outside = Path(root) / "secret.txt"
            outside.write_text("host-only-secret", encoding="utf-8")

            with (
                patch(
                    "cute_tool.tool.code_interpreter.tempfile.mkdtemp",
                    return_value=str(workspace),
                ),
                patch(
                    "cute_tool.tool.code_interpreter.download_all_files_in_path",
                    new=AsyncMock(
                        return_value=[
                            {"file_name": "secret.txt", "file_path": str(outside)}
                        ]
                    ),
                ),
                patch(
                    "cute_tool.tool.code_interpreter.build_permission_policy",
                    wraps=code_interpreter_module.build_permission_policy,
                ) as build_policy,
                patch(
                    "cute_tool.tool.code_interpreter.get_prompt",
                    return_value={"task_template": "{{ task }}"},
                ),
                patch(
                    "cute_tool.tool.code_interpreter.create_ci_agent",
                    return_value=FakeAgent(),
                ),
            ):
                with self.assertRaises(CodeExecutionPermissionError) as captured:
                    await self._consume_agent(
                        task="读取输入",
                        file_names=[str(outside)],
                        stream=False,
                    )

            self.assertEqual(
                "input_file_outside_workspace", captured.exception.blocked_reason
            )
            self.assertIn("输入文件不在工作区允许范围内", str(captured.exception))
            build_policy.assert_not_called()

    async def test_should_reject_parent_directory_escape(self):
        with tempfile.TemporaryDirectory() as root:
            workspace = Path(root) / "workspace"
            workspace.mkdir()
            outside = Path(root) / "secret.txt"
            outside.write_text("host-only-secret", encoding="utf-8")
            escape_path = workspace / ".." / "secret.txt"

            with (
                patch(
                    "cute_tool.tool.code_interpreter.tempfile.mkdtemp",
                    return_value=str(workspace),
                ),
                patch(
                    "cute_tool.tool.code_interpreter.download_all_files_in_path",
                    new=AsyncMock(
                        return_value=[
                            {"file_name": "secret.txt", "file_path": str(escape_path)}
                        ]
                    ),
                ),
                patch(
                    "cute_tool.tool.code_interpreter.build_permission_policy",
                    wraps=code_interpreter_module.build_permission_policy,
                ) as build_policy,
                patch(
                    "cute_tool.tool.code_interpreter.get_prompt",
                    return_value={"task_template": "{{ task }}"},
                ),
                patch(
                    "cute_tool.tool.code_interpreter.create_ci_agent",
                    return_value=FakeAgent(),
                ),
            ):
                with self.assertRaises(CodeExecutionPermissionError) as captured:
                    await self._consume_agent(
                        task="读取输入",
                        file_names=["../secret.txt"],
                        stream=False,
                    )

            self.assertEqual(
                "input_file_outside_workspace", captured.exception.blocked_reason
            )
            build_policy.assert_not_called()

    async def test_should_read_input_inside_workspace_and_register_it(self):
        with tempfile.TemporaryDirectory() as root:
            workspace = Path(root) / "workspace"
            workspace.mkdir()
            inside = workspace / "notes.txt"
            inside.write_text("workspace-only-content", encoding="utf-8")
            captured = {}

            class CapturingAgent:
                def run(self, task: str, stream: bool = True, max_steps: int = 10):
                    captured["task"] = task
                    return task

                def close_sandbox(self):
                    return None

            def fake_create_ci_agent(**kwargs):
                captured["policy"] = kwargs["permission_policy"]
                return CapturingAgent()

            with (
                patch(
                    "cute_tool.tool.code_interpreter.tempfile.mkdtemp",
                    return_value=str(workspace),
                ),
                patch(
                    "cute_tool.tool.code_interpreter.get_prompt",
                    return_value={
                        "task_template": "{{ files }}|{{ task }}|{{ input_file_names }}"
                    },
                ),
                patch(
                    "cute_tool.tool.code_interpreter.create_ci_agent",
                    side_effect=fake_create_ci_agent,
                ),
            ):
                outputs = await self._consume_agent(
                    task="读取输入",
                    file_names=["notes.txt"],
                    stream=False,
                )

            self.assertEqual(1, len(outputs))
            self.assertEqual(
                inside.resolve(),
                Path(captured["policy"].input_file_paths["notes.txt"]),
            )
            self.assertIn("workspace-only-content", captured["task"])
            self.assertIn("notes.txt", captured["task"])


if __name__ == "__main__":
    unittest.main()
