# -*- coding: utf-8 -*-
import tempfile
import unittest
from pathlib import Path
from unittest.mock import patch

from cute_tool.tool.dataprep._path_safe import open_read_text_nofollow


class PathSafeOpenTest(unittest.TestCase):
    def test_should_reject_symlink_before_opening(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "artifact.txt"
            path.write_text("secret", encoding="utf-8")
            with patch.object(Path, "is_symlink", return_value=True):
                with self.assertRaises(PermissionError):
                    open_read_text_nofollow(path)

    def test_should_read_regular_text_file(self):
        with tempfile.TemporaryDirectory() as temp_dir:
            path = Path(temp_dir) / "artifact.txt"
            path.write_text("safe", encoding="utf-8")
            with open_read_text_nofollow(path) as handle:
                self.assertEqual("safe", handle.read())


if __name__ == "__main__":
    unittest.main()
