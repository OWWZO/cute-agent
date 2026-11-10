from __future__ import annotations

import argparse
import importlib
import os
import runpy
import sys
import types
from pathlib import Path
from typing import Any

KNOWN_EVALUATOR_SYNTAX_ERROR = (
    '            except json.JSONDecodeError:\n'
    '                self.logger.error("Failed to parse JSON from assistant\'s answer.")\n'
    '                return {}'
)
KNOWN_EVALUATOR_SYNTAX_REPAIR = (
    '        except json.JSONDecodeError:\n'
    '            self.logger.error("Failed to parse JSON from assistant\'s answer.")\n'
    '            return {}'
)


def _patched_evaluator_text(path: Path) -> str:
    text = path.read_text(encoding="utf-8")
    count = text.count(KNOWN_EVALUATOR_SYNTAX_ERROR)
    if count != 1:
        raise RuntimeError(
            "expected exactly one known MARBLE evaluator syntax defect, "
            f"found {count}"
        )
    return text.replace(
        KNOWN_EVALUATOR_SYNTAX_ERROR,
        KNOWN_EVALUATOR_SYNTAX_REPAIR,
        1,
    )


def _load_patched_evaluator(source_dir: Path) -> None:
    evaluator_path = source_dir / "marble" / "evaluator" / "evaluator.py"
    importlib.import_module("marble.evaluator")
    module = types.ModuleType("marble.evaluator.evaluator")
    module.__file__ = str(evaluator_path)
    module.__package__ = "marble.evaluator"
    sys.modules["marble.evaluator.evaluator"] = module
    code = compile(
        _patched_evaluator_text(evaluator_path),
        str(evaluator_path),
        "exec",
    )
    exec(code, module.__dict__)


def _load_research_offline_tools(source_dir: Path) -> None:
    module = types.ModuleType("marble.environments.research_utils.paper_collector")
    module.__file__ = str(
        source_dir
        / "marble"
        / "environments"
        / "research_utils"
        / "paper_collector.py"
    )
    module.__package__ = "marble.environments.research_utils"

    def _offline(*args: Any, **kwargs: Any) -> None:
        raise ValueError(
            "offline compatibility run: external research tools are disabled"
        )

    for name in (
        "get_related_papers",
        "get_recent_papers",
        "get_paper_by_keyword",
        "get_paper_by_arxiv_id",
        "get_paper_by_title",
    ):
        setattr(module, name, _offline)

    def _offline_publications(*args: Any, **kwargs: Any) -> None:
        raise ValueError(
            "offline compatibility run: publication lookup is disabled"
        )

    module.collect_publications_and_coauthors = _offline_publications
    sys.modules[
        "marble.environments.research_utils.paper_collector"
    ] = module
    sys.modules[
        "marble.environments.research_utils.profile_collector"
    ] = types.ModuleType(
        "marble.environments.research_utils.profile_collector"
    )
    sys.modules[
        "marble.environments.research_utils.profile_collector"
    ].collect_publications_and_coauthors = _offline_publications


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--source-dir", required=True)
    parser.add_argument("--config-path", required=True)
    parser.add_argument("--repair-evaluator-syntax", action="store_true")
    parser.add_argument(
        "--offline-tools",
        action="store_true",
        help="disable external research tools for an offline compatibility run",
    )
    args = parser.parse_args()

    source_dir = Path(args.source_dir).resolve()
    sys.path.insert(0, str(source_dir))
    if args.repair_evaluator_syntax:
        _load_patched_evaluator(source_dir)
    if args.offline_tools:
        _load_research_offline_tools(source_dir)

    runtime_dir = source_dir / "marble"
    (runtime_dir / "logs").mkdir(parents=True, exist_ok=True)
    os.chdir(runtime_dir)
    sys.argv = [
        str(runtime_dir / "main.py"),
        "--config_path",
        str(Path(args.config_path).resolve()),
    ]
    runpy.run_path(str(runtime_dir / "main.py"), run_name="__main__")


if __name__ == "__main__":
    main()
