# -*- coding: utf-8 -*-
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from cute_tool.tool.docgen.service import fill_template


class DocgenTemplateSecurityTest(unittest.TestCase):
    def test_private_url_should_be_rejected(self):
        response = type(
            "Response",
            (),
            {"status_code": 200, "text": "safe template"},
        )()
        response.raise_for_status = lambda: None
        with patch(
            "socket.getaddrinfo",
            return_value=[(None, None, None, None, ("127.0.0.1", 0))],
        ), patch("requests.get", return_value=response) as get:
            with self.assertRaises(ValueError) as context:
                fill_template(
                    {
                        "template_source": "url",
                        "template_url": "http://localhost:8000/template.jinja",
                    }
                )

        self.assertIn("Private or internal", str(context.exception))
        get.assert_not_called()

    def test_non_http_template_url_should_be_rejected(self):
        with self.assertRaises(ValueError) as context:
            fill_template(
                {
                    "template_source": "url",
                    "template_url": "file:///etc/passwd",
                }
            )

        self.assertIn("Only http(s)", str(context.exception))

    def test_cloud_metadata_address_should_be_rejected(self):
        response = type(
            "Response",
            (),
            {"status_code": 200, "text": "safe template"},
        )()
        response.raise_for_status = lambda: None
        with patch(
            "socket.getaddrinfo",
            return_value=[(None, None, None, None, ("169.254.169.254", 0))],
        ), patch("requests.get", return_value=response) as get:
            with self.assertRaises(ValueError) as context:
                fill_template(
                    {
                        "template_source": "url",
                        "template_url": "http://metadata.example/template.jinja",
                    }
                )

        self.assertIn("Private or internal", str(context.exception))
        get.assert_not_called()

    def test_template_url_redirect_should_be_rejected(self):
        response = type(
            "RedirectResponse",
            (),
            {"status_code": 302, "headers": {"Location": "http://127.0.0.1/secret"}},
        )()
        response.raise_for_status = lambda: None
        getaddrinfo_result = [(None, None, None, None, ("93.184.216.34", 0))]

        with patch(
            "socket.getaddrinfo",
            return_value=getaddrinfo_result,
        ), patch("requests.get", return_value=response) as get:
            with self.assertRaises(ValueError) as context:
                fill_template(
                    {
                        "template_source": "url",
                        "template_url": "http://example.com/template.jinja",
                    }
                )

        self.assertIn("redirects are not allowed", str(context.exception))
        get.assert_called_once()
        self.assertFalse(get.call_args.kwargs["allow_redirects"])

    def test_remote_template_expression_should_be_sandboxed(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            url_path = Path(temp_dir) / "template.txt"
            url_path.write_text(
                "{{ ''.__class__.__mro__[1].__subclasses__()[0] }}",
                encoding="utf-8",
            )
            output_path = Path(temp_dir) / "out.txt"
            with self.assertRaises(Exception) as context:
                fill_template(
                    {
                        "template_source": "file",
                        "template_path": str(url_path),
                        "output_path": str(output_path),
                    }
                )

        self.assertIn("unsafe", str(context.exception).lower())


if __name__ == "__main__":
    unittest.main()
