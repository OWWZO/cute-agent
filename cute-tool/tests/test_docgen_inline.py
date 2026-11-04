# -*- coding: utf-8 -*-
import unittest

from cute_tool.docgen.markdown import parse_inline


class DocgenInlineParseTest(unittest.TestCase):
    def test_parse_inline_should_visit_inline_children(self):
        spans = parse_inline("**bold** `code` $x+1$")

        self.assertIn("bold", [span.text for span in spans])
        self.assertIn("code", [span.text for span in spans])
        self.assertIn("x+1", [span.text for span in spans])
        self.assertTrue(any(span.bold for span in spans if span.text == "bold"))
        self.assertTrue(any(span.code for span in spans if span.text == "code"))
        self.assertTrue(any(span.math for span in spans if span.text == "x+1"))


if __name__ == "__main__":
    unittest.main()
