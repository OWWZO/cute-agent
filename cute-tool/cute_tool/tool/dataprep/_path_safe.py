# -*- coding: utf-8 -*-
"""Minimal path-safe open for dataprep local artifacts."""
from __future__ import annotations

import errno
import os
import stat
from pathlib import Path
from typing import IO, Any


def open_read_text_nofollow(path: Path | str, **kwargs: Any) -> IO[str]:
    p = Path(path)
    if p.is_symlink():
        raise PermissionError("symbolic links are not allowed")
    flags = os.O_RDONLY | getattr(os, "O_NOFOLLOW", 0)
    try:
        fd = os.open(p, flags)
    except OSError as exc:
        if exc.errno in {errno.ELOOP, getattr(errno, "EMLINK", errno.ELOOP)}:
            raise PermissionError("symbolic links are not allowed") from exc
        if isinstance(exc, FileNotFoundError):
            raise FileNotFoundError("file not found") from None
        if isinstance(exc, PermissionError):
            raise PermissionError("file is not readable") from None
        raise
    try:
        if not stat.S_ISREG(os.fstat(fd).st_mode):
            raise OSError("path is not a regular file")
    except Exception:
        os.close(fd)
        raise
    kwargs.setdefault("encoding", "utf-8")
    return os.fdopen(fd, "r", **kwargs)
