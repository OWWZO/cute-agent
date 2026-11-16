from __future__ import annotations

from ...judge_benchmarks.client import OpenAICompatibleJudge
from ..config import SemanticJudgeConfig


class SemanticJudgeProvider:
    def __init__(
        self,
        config: SemanticJudgeConfig,
        *,
        client: OpenAICompatibleJudge | None = None,
    ) -> None:
        if not config.model:
            raise ValueError("semantic judge model is not configured")
        if client is None:
            if not config.base_url or not config.api_key:
                raise ValueError(
                    "semantic judge requires base URL and API key"
                )
            client = OpenAICompatibleJudge(
                model=config.model,
                base_url=config.base_url,
                api_key=config.api_key,
                temperature=0.0,
                timeout_seconds=config.timeout_seconds,
            )
        self.config = config
        self.client = client

    def complete(self, messages: list[dict[str, str]]) -> str:
        return self.client.complete(
            messages,
            timeout_seconds=self.config.timeout_seconds,
        )
