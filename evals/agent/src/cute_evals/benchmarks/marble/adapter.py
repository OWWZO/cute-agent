from __future__ import annotations

import hashlib
import json
from dataclasses import dataclass, field
from pathlib import Path
from typing import Any

import yaml

from ...judge_benchmarks.base import LicenseBlockedError

PACKAGE_ROOT = Path(__file__).resolve().parent
DEFAULT_MANIFEST = PACKAGE_ROOT / "manifest.yaml"
DOMAIN_NAMES = ("bargaining", "coding", "database", "minecraft", "research")


def _sha256(path: Path) -> str:
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


@dataclass(frozen=True, slots=True)
class MarbleTask:
    id: str
    domain: str
    task_id: str | int | None
    agent_count: int
    scenario: str
    raw: dict[str, Any] = field(repr=False)


@dataclass(slots=True)
class MarbleAdapter:
    source_dir: Path
    manifest_path: Path = DEFAULT_MANIFEST
    name: str = "multiagentbench-marble"
    version: str = ""
    upstream: str = ""
    score_namespace: str = "agent-benchmark"
    score_label: str = "blocked-license"
    manifest: dict[str, Any] = field(init=False, repr=False, compare=False)

    def __post_init__(self) -> None:
        self.source_dir = self.source_dir.resolve()
        self.manifest_path = self.manifest_path.resolve()
        raw = yaml.safe_load(self.manifest_path.read_text(encoding="utf-8"))
        if not isinstance(raw, dict):
            raise TypeError(f"invalid MARBLE manifest: {self.manifest_path}")
        self.manifest = raw
        self.name = str(raw["name"])
        self.version = str(raw["version"])
        self.upstream = str(raw["upstream"])
        self.score_namespace = str(raw["scoreNamespace"])
        self.score_label = str(raw["scoreLabel"])

    @property
    def license_status(self) -> str:
        return str(self.manifest.get("licenseStatus") or "unverified")

    def _path(self, relative: str) -> Path:
        path = (self.source_dir / relative).resolve()
        if self.source_dir not in path.parents and path != self.source_dir:
            raise ValueError(f"official asset escapes source directory: {relative}")
        return path

    def verify_assets(self) -> dict[str, Any]:
        verified: dict[str, Any] = {"domains": {}, "scripts": {}}
        for domain, spec in self.manifest["domains"].items():
            path = self._path(str(spec["file"]))
            if not path.is_file():
                raise FileNotFoundError(path)
            actual = _sha256(path)
            if actual != str(spec["sha256"]).lower():
                raise ValueError(
                    f"MARBLE {domain} hash mismatch: "
                    f"expected {spec['sha256']}, got {actual}"
                )
            verified["domains"][domain] = {
                "path": str(path),
                "sha256": actual,
                "size": path.stat().st_size,
            }
        for name, spec in self.manifest["scripts"].items():
            path = self._path(str(spec["file"]))
            if not path.is_file():
                raise FileNotFoundError(path)
            actual = _sha256(path)
            if actual != str(spec["sha256"]).lower():
                raise ValueError(
                    f"MARBLE {name} script hash mismatch: "
                    f"expected {spec['sha256']}, got {actual}"
                )
            verified["scripts"][name] = {
                "path": str(path),
                "sha256": actual,
            }
        return verified

    def assert_run_allowed(self) -> None:
        if self.license_status != "approved":
            raise LicenseBlockedError(
                self.name,
                self.license_status,
                str(self.manifest.get("licenseEvidence") or ""),
            )

    def _limit(self, split: str) -> int:
        splits = self.manifest.get("splits") or {}
        if split not in splits:
            raise ValueError(f"unsupported MARBLE split: {split}")
        return int(splits[split])

    def _rows(self, domain: str) -> list[dict[str, Any]]:
        spec = self.manifest["domains"][domain]
        path = self._path(str(spec["file"]))
        rows: list[dict[str, Any]] = []
        for line_no, line in enumerate(
            path.read_text(encoding="utf-8").splitlines(),
            start=1,
        ):
            if not line.strip():
                continue
            try:
                row = json.loads(line)
            except json.JSONDecodeError as exc:
                raise ValueError(f"{path}:{line_no}: invalid JSON: {exc}") from exc
            if not isinstance(row, dict):
                raise ValueError(f"{path}:{line_no}: expected a JSON object")
            rows.append(row)
        return rows

    def tasks(self, split: str) -> list[MarbleTask]:
        limit = self._limit(split)
        tasks: list[MarbleTask] = []
        for domain in DOMAIN_NAMES:
            rows = self._rows(domain)
            selected = rows if limit < 0 else rows[:limit]
            for index, row in enumerate(selected):
                task_id = row.get("task_id", index)
                agents = row.get("agents")
                tasks.append(
                    MarbleTask(
                        id=f"{domain}:{task_id}",
                        domain=domain,
                        task_id=task_id,
                        agent_count=len(agents) if isinstance(agents, list) else 0,
                        scenario=str(row.get("scenario") or domain),
                        raw=row,
                    )
                )
        return tasks

    @staticmethod
    def dataset_hash(assets: dict[str, Any]) -> str:
        payload = "\n".join(
            f"{domain}:{spec['sha256']}"
            for domain, spec in sorted(assets["domains"].items())
        )
        return "sha256:" + hashlib.sha256(
            payload.encode("utf-8")
        ).hexdigest()

    def report_metadata(self) -> dict[str, Any]:
        return {
            "name": self.name,
            "version": self.version,
            "upstream": self.upstream,
            "upstreamCommit": self.manifest.get("upstreamCommit"),
            "licenseStatus": self.license_status,
            "scoreNamespace": self.score_namespace,
            "scoreLabel": self.score_label,
        }
