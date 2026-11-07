from __future__ import annotations

import argparse
from pathlib import Path
from typing import Any

from .dataset_lock import dataset_hash, load_dataset_manifest


def load_rag_dataset_manifest(cases_dir: Path) -> dict[str, Any]:
    return load_dataset_manifest(cases_dir)


def main() -> None:
    parser = argparse.ArgumentParser(description="Validate or print a RAG dataset hash")
    parser.add_argument("cases_dir", type=Path)
    parser.add_argument("--validate", action="store_true")
    args = parser.parse_args()
    if args.validate:
        manifest = load_rag_dataset_manifest(args.cases_dir)
        print(manifest["datasetHash"])
    else:
        print(dataset_hash(args.cases_dir))


if __name__ == "__main__":
    main()
