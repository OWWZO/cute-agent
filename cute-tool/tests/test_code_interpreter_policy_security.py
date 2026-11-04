# -*- coding: utf-8 -*-
import unittest
from unittest.mock import patch

from cute_tool.tool.code_interpreter_policy import (
    CodeExecutionPermissionError,
    build_permission_policy,
    validate_code_against_policy,
)


class CodeInterpreterPolicySecurityTest(unittest.TestCase):
    def setUp(self):
        self.env_patcher = patch.dict(
            "os.environ",
            {
                "CODE_INTERPRETER_ENABLE_PRE_EXECUTION_VALIDATION": "true",
                "CODE_INTERPRETER_ENABLE_PATH_SANDBOX": "true",
            },
            clear=False,
        )
        self.env_patcher.start()
        self.addCleanup(self.env_patcher.stop)
        self.policy = build_permission_policy(
            profile="analysis",
            workspace_root=r"D:\temp\ci-workspace",
            output_dir=r"D:\temp\ci-workspace\output",
            input_files=[],
        )

    def test_dynamic_path_should_fail_closed(self):
        with self.assertRaises(CodeExecutionPermissionError) as context:
            validate_code_against_policy(
                "import pandas as pd\ndf = pd.DataFrame({'a': [1]})\n"
                "df.to_excel(pd.Timestamp.now().strftime('%Y.xlsx'))",
                self.policy,
            )

        self.assertEqual("unresolved_path", context.exception.blocked_reason)

    def test_aliased_blocked_call_should_be_rejected(self):
        with self.assertRaises(CodeExecutionPermissionError) as context:
            validate_code_against_policy("f = eval\nf('1 + 1')", self.policy)

        self.assertEqual("blocked_call", context.exception.blocked_reason)

    def test_getattr_alias_should_be_rejected(self):
        with self.assertRaises(CodeExecutionPermissionError) as context:
            validate_code_against_policy(
                "f = getattr\nf(__builtins__, 'eval')('1 + 1')",
                self.policy,
            )

        self.assertEqual("blocked_call", context.exception.blocked_reason)

    def test_nested_blocked_alias_should_be_rejected(self):
        with self.assertRaises(CodeExecutionPermissionError) as context:
            validate_code_against_policy(
                "def run():\n"
                "    f = eval\n"
                "    return f('1 + 1')",
                self.policy,
            )

        self.assertEqual("blocked_call", context.exception.blocked_reason)


if __name__ == "__main__":
    unittest.main()
