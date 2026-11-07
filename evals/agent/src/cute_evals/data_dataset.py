from __future__ import annotations

from pathlib import Path
from typing import Any

from .dataset_lock import load_dataset_manifest


def fixture_files(cases_dir: Path) -> list[Path]:
    fixtures_dir = cases_dir / "fixtures"
    return sorted(fixtures_dir.glob("*")) if fixtures_dir.exists() else []


def load_data_dataset_manifest(cases_dir: Path) -> dict[str, Any]:
    return load_dataset_manifest(cases_dir, extra_files=fixture_files(cases_dir))


def main() -> None:
    import argparse

    parser = argparse.ArgumentParser(description="Print or validate a data dataset hash")
    parser.add_argument("cases_dir", type=Path)
    parser.add_argument("--validate", action="store_true")
    args = parser.parse_args()
    if args.validate:
        print(load_data_dataset_manifest(args.cases_dir)["datasetHash"])
    else:
        from .dataset_lock import dataset_hash

        print(dataset_hash(args.cases_dir, extra_files=fixture_files(args.cases_dir)))


if __name__ == "__main__":
    main()
