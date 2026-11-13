# -*- coding: utf-8 -*-
import unittest

from cute_tool.util.path_safety import safe_path_segment


class SafePathSegmentTest(unittest.TestCase):
    def test_common_chinese_filename_punctuation_should_be_allowed(self):
        for name in (
            "销售报告(终版).pdf",
            "报告（2026）[v2].pdf",
            "报告【终版】，2026.pdf",
        ):
            with self.subTest(name=name):
                self.assertEqual(name, safe_path_segment(name))

    def test_unsafe_path_segments_should_still_be_rejected(self):
        for name in (
            "../../etc/passwd",
            "..",
            "a/b",
            "a\\b",
            ".hidden",
            "a\nb",
            "a\0b",
            "CON",
            "NUL.txt",
            "COM1.log",
            "LPT9",
        ):
            with self.subTest(name=name):
                with self.assertRaises(ValueError):
                    safe_path_segment(name)


if __name__ == "__main__":
    unittest.main()
