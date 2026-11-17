from __future__ import annotations

import json
import os
import re
import shutil
import time
from pathlib import Path
from typing import Any


_JOB_ID = re.compile(r"^[A-Za-z0-9][A-Za-z0-9_-]{0,127}$")


class ReviewStore:
    def __init__(self, root: Path) -> None:
        self.root = root
        self.root.mkdir(parents=True, exist_ok=True)

    def job_dir(self, job_id: str) -> Path:
        if not _JOB_ID.fullmatch(job_id):
            raise ValueError("invalid review job id")
        return self.root / job_id

    def create_job_dir(self, job_id: str) -> Path:
        path = self.job_dir(job_id)
        path.mkdir(parents=True, exist_ok=False)
        (path / "trace").mkdir()
        return path

    def write(self, path: Path, payload: Any) -> None:
        self.write_text(
            path,
            json.dumps(payload, ensure_ascii=False, indent=2),
        )

    def write_text(self, path: Path, content: str) -> None:
        path.parent.mkdir(parents=True, exist_ok=True)
        temporary = path.with_name(f".{path.name}.tmp")
        temporary.write_text(content, encoding="utf-8")
        os.replace(temporary, path)

    def read(self, path: Path) -> Any:
        return json.loads(path.read_text(encoding="utf-8"))

    def list_job_ids(self) -> list[str]:
        items = [
            item
            for item in self.root.iterdir()
            if item.is_dir() and (item / "job.json").is_file()
        ]
        items.sort(
            key=lambda item: (item / "job.json").stat().st_mtime,
            reverse=True,
        )
        return [item.name for item in items]

    def purge_expired(self, retention_days: int) -> int:
        if retention_days <= 0:
            return 0
        root = self.root.resolve()
        cutoff = time.time() - retention_days * 24 * 60 * 60
        removed = 0
        for item in self.root.iterdir():
            if item.is_symlink() or not item.is_dir():
                continue
            job_file = item / "job.json"
            if not job_file.is_file():
                continue
            try:
                resolved = item.resolve()
                if resolved.parent != root or job_file.stat().st_mtime >= cutoff:
                    continue
                shutil.rmtree(resolved)
                removed += 1
            except OSError:
                # A concurrent cleanup or a partially written job must not
                # prevent the review service from starting.
                continue
        return removed
