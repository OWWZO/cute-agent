# -*- coding: utf-8 -*-
import unittest

from cute_tool.tool.docread._recovery import recover_doc_tool_args


class RecoveryEncodingTest(unittest.TestCase):
    def test_non_ascii_json_escape_should_decode_as_utf8(self):
        raw = r'{"operation":"write","file_path":"out.txt","data":"\u4e2d\u6587"}'

        result = recover_doc_tool_args(raw, content_key="data")

        self.assertEqual("中文", result["data"])

    def test_literal_non_ascii_should_not_be_mangled(self):
        raw = '{"operation":"write","file_path":"out.txt","data":"中文"}'

        result = recover_doc_tool_args(raw, content_key="data")

        self.assertEqual("中文", result["data"])


if __name__ == "__main__":
    unittest.main()
