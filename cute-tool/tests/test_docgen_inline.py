# -*- coding: utf-8 -*-
import unittest
from types import SimpleNamespace
from unittest.mock import patch

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

    def test_parse_inline_should_handle_flat_tokens_without_children(self):
        flat_tokens = [
            SimpleNamespace(type="strong_open", content="", children=None),
            SimpleNamespace(type="text", content="bold", children=None),
            SimpleNamespace(type="strong_close", content="", children=None),
        ]
        fake_parser = SimpleNamespace(parseInline=lambda _text: flat_tokens)

        with patch("cute_tool.docgen.markdown._parser", return_value=fake_parser):
            spans = parse_inline("**bold**")

        bold_spans = [span for span in spans if span.text == "bold"]
        self.assertEqual(1, len(bold_spans))
        self.assertTrue(bold_spans[0].bold)


if __name__ == "__main__":
    unittest.main()
