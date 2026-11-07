from __future__ import annotations

import ast
import hashlib
import math
import re
from functools import lru_cache
from pathlib import Path
from typing import Any, ClassVar

import pandas as pd


class Spider2OfficialEvaluator:
    """Run only Spider2's official comparison functions without BigQuery imports."""

    _FUNCTIONS: ClassVar[set[str]] = {
        "load_gold_csv",
        "extract_sql_query",
        "compare_multi_pandas_table",
        "compare_pandas_table",
    }

    def __init__(self, evaluator_path: Path, expected_sha256: str) -> None:
        self.evaluator_path = evaluator_path.resolve()
        actual = hashlib.sha256(self.evaluator_path.read_bytes()).hexdigest()
        expected = expected_sha256.lower()
        if actual != expected:
            raise ValueError(
                "Spider2 evaluator hash mismatch: "
                f"expected {expected}, got {actual}"
            )
        source = self.evaluator_path.read_text(encoding="utf-8")
        tree = ast.parse(source)
        functions = [
            node
            for node in tree.body
            if isinstance(node, ast.FunctionDef) and node.name in self._FUNCTIONS
        ]
        found = {node.name for node in functions}
        if found != self._FUNCTIONS:
            missing = ", ".join(sorted(self._FUNCTIONS - found))
            raise ValueError(
                f"Spider2 evaluator is missing official functions: {missing}"
            )
        namespace: dict[str, Any] = {
            "lru_cache": lru_cache,
            "math": math,
            "pd": pd,
            "re": re,
        }
        module = ast.Module(body=functions, type_ignores=[])
        exec(  # noqa: S102 - only hash-pinned official functions are executed
            compile(module, str(self.evaluator_path), "exec"),
            namespace,
        )
        self._load_gold_csv = namespace["load_gold_csv"]
        self._extract_sql_query = namespace["extract_sql_query"]
        self._compare_pandas_table = namespace["compare_pandas_table"]
        self._compare_multi_pandas_table = namespace["compare_multi_pandas_table"]

    def extract_sql(self, sql: str) -> str:
        return str(self._extract_sql_query(str(sql or ""))).strip()

    def compare(
        self,
        prediction: pd.DataFrame,
        gold_paths: list[Path],
        *,
        condition_cols: Any,
        ignore_order: bool,
    ) -> int:
        gold_frames = [
            self._load_gold_csv(str(path.resolve())) for path in gold_paths
        ]
        if len(gold_frames) == 1:
            return int(
                self._compare_pandas_table(
                    prediction,
                    gold_frames[0],
                    condition_cols,
                    ignore_order,
                )
            )
        return int(
            self._compare_multi_pandas_table(
                prediction,
                gold_frames,
                condition_cols,
                ignore_order,
            )
        )
