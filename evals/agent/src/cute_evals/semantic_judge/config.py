from __future__ import annotations

import os
from dataclasses import dataclass


@dataclass(frozen=True, slots=True)
class SemanticJudgeConfig:
    model: str | None = None
    base_url: str | None = None
    api_key: str | None = None
    mode: str = "advisory"
    timeout_seconds: int = 120
    max_evidence_chars: int = 120_000
    max_tool_result_chars: int = 12_000
    max_file_chars: int = 24_000
    require_evidence: bool = True
    output_language: str = "English"

    @property
    def configured(self) -> bool:
        return bool(self.model)

    @property
    def openai_compatible(self) -> bool:
        return bool(self.base_url and self.api_key)

    @classmethod
    def from_env(cls) -> SemanticJudgeConfig:
        return cls(
            model=os.environ.get("CUTE_EVAL_JUDGE_MODEL"),
            base_url=os.environ.get("CUTE_EVAL_JUDGE_BASE_URL"),
            api_key=os.environ.get("CUTE_EVAL_JUDGE_API_KEY"),
        )
