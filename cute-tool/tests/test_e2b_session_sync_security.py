# -*- coding: utf-8 -*-
import json
import tempfile
import unittest
import uuid
from pathlib import Path
from types import SimpleNamespace

from cute_tool.tool.e2b_session_sync import download_changed_files, push_workspace


class E2BSessionSyncSecurityTest(unittest.TestCase):
    def test_malicious_relative_path_should_not_escape_workspace(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            workspace = Path(temp_dir)
            before = {}
            target = workspace.parent / f"outside-{uuid.uuid4().hex}.txt"
            rel = f"../{target.name}"
            files = SimpleNamespace(
                read=lambda path, format="bytes": b"payload"
            )
            commands = SimpleNamespace(
                run=lambda command, cwd=None, timeout=None: SimpleNamespace(
                    exit_code=0,
                    stdout="__SESSION_SNAPSHOT__"
                    + json.dumps({rel: [7, 1]})
                    + "\n",
                    stderr="",
                )
            )
            sandbox = SimpleNamespace(files=files, commands=commands)
            produced_paths = []

            changed = download_changed_files(
                sandbox,
                "/workspace/session",
                workspace,
                before,
                produced_paths,
            )

            self.assertEqual([], changed)
            self.assertEqual([], produced_paths)
            self.assertFalse(target.exists())

    def test_absolute_remote_path_should_not_escape_workspace(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            workspace = Path(temp_dir)
            target = workspace.parent / f"outside-{uuid.uuid4().hex}.txt"
            files = SimpleNamespace(read=lambda path, format="bytes": b"payload")
            commands = SimpleNamespace(
                run=lambda command, cwd=None, timeout=None: SimpleNamespace(
                    exit_code=0,
                    stdout="__SESSION_SNAPSHOT__"
                    + json.dumps({target.as_posix(): [7, 1]})
                    + "\n",
                    stderr="",
                )
            )
            sandbox = SimpleNamespace(files=files, commands=commands)

            changed = download_changed_files(
                sandbox,
                "/workspace/session",
                workspace,
                {},
            )

            self.assertEqual([], changed)
            self.assertFalse(target.exists())

    def test_backslash_path_should_not_escape_workspace(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            workspace = Path(temp_dir)
            target = workspace.parent / f"outside-{uuid.uuid4().hex}.txt"
            rel = f"..\\{target.name}"
            files = SimpleNamespace(read=lambda path, format="bytes": b"payload")
            commands = SimpleNamespace(
                run=lambda command, cwd=None, timeout=None: SimpleNamespace(
                    exit_code=0,
                    stdout="__SESSION_SNAPSHOT__"
                    + json.dumps({rel: [7, 1]})
                    + "\n",
                    stderr="",
                )
            )
            sandbox = SimpleNamespace(files=files, commands=commands)

            changed = download_changed_files(
                sandbox,
                "/workspace/session",
                workspace,
                {},
            )

            self.assertEqual([], changed)
            self.assertFalse(target.exists())

    def test_malicious_session_id_should_be_rejected_before_remote_mkdir(self):
        commands_run = []
        sandbox = SimpleNamespace(
            files=SimpleNamespace(),
            commands=SimpleNamespace(
                run=lambda command, **kwargs: commands_run.append(command)
                or SimpleNamespace(exit_code=0, stdout="", stderr="")
            ),
        )

        with tempfile.TemporaryDirectory() as temp_dir:
            with self.assertRaises(ValueError):
                push_workspace(
                    sandbox,
                    Path(temp_dir),
                    "../escape",
                    owner_key="owner",
                    sandbox_id="sandbox",
                    generation=1,
                )

        self.assertEqual([], commands_run)


if __name__ == "__main__":
    unittest.main()
