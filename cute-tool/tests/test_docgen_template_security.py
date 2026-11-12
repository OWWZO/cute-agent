# -*- coding: utf-8 -*-
import http.server
import socket
import tempfile
import threading
import unittest
from pathlib import Path
from unittest.mock import patch

import urllib3.util.connection

from cute_tool.docgen.templates import load_template
from cute_tool.docgen.themes import get_theme
from cute_tool.docgen.theming import load_custom_theme_payload
from cute_tool.tool.docgen.service import fill_template


def _getaddrinfo_record(address):
    return [(socket.AF_INET, socket.SOCK_STREAM, 6, "", (address, 0))]


class DocgenTemplateSecurityTest(unittest.TestCase):
    def test_private_url_should_be_rejected(self):
        with patch(
            "socket.getaddrinfo",
            return_value=_getaddrinfo_record("127.0.0.1"),
        ), patch("requests.Session.get") as get:
            with self.assertRaises(ValueError) as context:
                fill_template(
                    {
                        "template_source": "url",
                        "template_url": "http://localhost/template.jinja",
                    }
                )

        self.assertIn("Private or internal", str(context.exception))
        get.assert_not_called()

    def test_any_private_record_should_be_rejected(self):
        records = _getaddrinfo_record("93.184.216.34")
        records += _getaddrinfo_record("10.0.0.8")
        with patch("socket.getaddrinfo", return_value=records), patch(
            "requests.Session.get"
        ) as get:
            with self.assertRaises(ValueError) as context:
                fill_template(
                    {
                        "template_source": "url",
                        "template_url": "http://example.com/template.jinja",
                    }
                )

        self.assertIn("Private or internal", str(context.exception))
        get.assert_not_called()

    def test_non_standard_port_should_be_rejected(self):
        with patch("socket.getaddrinfo") as resolve:
            with self.assertRaises(ValueError) as context:
                fill_template(
                    {
                        "template_source": "url",
                        "template_url": "http://example.com:8080/template.jinja",
                    }
                )

        self.assertIn("port must be 80 or 443", str(context.exception))
        resolve.assert_not_called()

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
        with patch(
            "socket.getaddrinfo",
            return_value=_getaddrinfo_record("169.254.169.254"),
        ), patch("requests.Session.get") as get:
            with self.assertRaises(ValueError) as context:
                fill_template(
                    {
                        "template_source": "url",
                        "template_url": "http://metadata.example/template.jinja",
                    }
                )

        self.assertIn("Private or internal", str(context.exception))
        get.assert_not_called()

    def test_private_ipv6_addresses_should_be_rejected(self):
        for address in ("::1", "fe80::1", "fd00::1", "::ffff:127.0.0.1"):
            with self.subTest(address=address), patch(
                "socket.getaddrinfo",
                return_value=_getaddrinfo_record(address),
            ), patch("requests.Session.get") as get:
                with self.assertRaises(ValueError) as context:
                    fill_template(
                        {
                            "template_source": "url",
                            "template_url": "http://example.com/template.jinja",
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

        with patch(
            "socket.getaddrinfo",
            return_value=_getaddrinfo_record("93.184.216.34"),
        ), patch("requests.Session.get", return_value=response) as get:
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

    def test_validated_public_address_is_pinned_and_host_is_preserved(self):
        received = {}

        class Handler(http.server.BaseHTTPRequestHandler):
            def do_GET(self):
                received["host"] = self.headers.get("Host")
                body = b"safe template"
                self.send_response(200)
                self.send_header("Content-Length", str(len(body)))
                self.end_headers()
                self.wfile.write(body)

            def log_message(self, *args):
                return

        server = http.server.ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        server_thread = threading.Thread(target=server.serve_forever, daemon=True)
        server_thread.start()
        self.addCleanup(server.server_close)
        self.addCleanup(server.shutdown)

        real_getaddrinfo = socket.getaddrinfo
        real_create_connection = urllib3.util.connection.create_connection
        resolved_hosts = []
        connection_targets = []

        def fake_getaddrinfo(host, port, *args, **kwargs):
            if host.lower() == "example.com":
                resolved_hosts.append(host)
                address = "93.184.216.34" if len(resolved_hosts) == 1 else "127.0.0.1"
                return _getaddrinfo_record(address)
            return real_getaddrinfo(host, port, *args, **kwargs)

        def redirect_to_local(address, timeout=None, **kwargs):
            connection_targets.append(address)
            return real_create_connection(
                ("127.0.0.1", server.server_address[1]),
                timeout=timeout,
                **kwargs,
            )

        with patch("socket.getaddrinfo", side_effect=fake_getaddrinfo), patch(
            "urllib3.util.connection.create_connection",
            side_effect=redirect_to_local,
        ):
            result = fill_template(
                {
                    "template_source": "url",
                    "template_url": "http://example.com/template.jinja",
                }
            )

        self.assertTrue(result["success"])
        self.assertEqual("safe template", result["rendered"])
        self.assertEqual(("93.184.216.34", 80), connection_targets[0])
        self.assertEqual(["example.com"], resolved_hosts)
        self.assertEqual("example.com", received["host"])

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

    def test_template_loader_should_reject_path_traversal(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            home = Path(temp_dir)
            (home / "secret.yaml").write_text(
                "name: secret\nkind: document\ncontent: leaked\n",
                encoding="utf-8",
            )
            with patch.dict(
                "os.environ", {"CUTE_DOCGEN_HOME": str(home)}, clear=False
            ):
                with self.assertRaises(ValueError):
                    load_template("../../secret")

    def test_theme_lookup_should_not_read_outside_styles_directory(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            home = Path(temp_dir)
            (home / "templates" / "styles").mkdir(parents=True)
            (home / "secret.yaml").write_text(
                "fonts:\n  heading: Leaked Font\n",
                encoding="utf-8",
            )
            with patch.dict(
                "os.environ", {"CUTE_DOCGEN_HOME": str(home)}, clear=False
            ):
                theme = get_theme("../../secret")

        self.assertNotEqual("Leaked Font", theme.fonts.heading)
        self.assertNotEqual("../../secret", theme.name)

    def test_custom_theme_payload_loader_should_reject_path_traversal(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            home = Path(temp_dir)
            (home / "secret.yaml").write_text(
                "colors:\n  primary: '#000000'\n",
                encoding="utf-8",
            )
            with patch.dict(
                "os.environ", {"CUTE_DOCGEN_HOME": str(home)}, clear=False
            ):
                with self.assertRaises(ValueError):
                    load_custom_theme_payload("../../secret")


if __name__ == "__main__":
    unittest.main()
