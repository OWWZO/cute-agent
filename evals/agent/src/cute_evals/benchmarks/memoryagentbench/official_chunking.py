"""Official MemoryAgentBench context chunking.

The implementation is copied from `utils/eval_other_utils.py` at the pinned
upstream revision. nltk and tiktoken are optional at import time so deterministic
adapter tests do not need the official runtime dependencies.
"""

from __future__ import annotations


def chunk_text_into_sentences(
    text: str,
    model_name: str = "gpt-4o-mini",
    chunk_size: int = 4096,
) -> list[str]:
    try:
        import nltk
        import tiktoken
    except ImportError as exc:
        raise RuntimeError(
            "MemoryAgentBench chunking requires the 'memoryagentbench' extra"
        ) from exc

    for resource in ("tokenizers/punkt", "tokenizers/punkt_tab"):
        try:
            nltk.data.find(resource)
        except LookupError:
            nltk.download(resource.rsplit("/", 1)[-1], quiet=True)
    encoding = tiktoken.encoding_for_model(model_name)
    sentences = nltk.sent_tokenize(text)
    chunks: list[str] = []
    current_sentences: list[str] = []
    current_tokens = 0

    for sentence in sentences:
        sentence_tokens = len(
            encoding.encode(sentence, allowed_special={"<|endoftext|>"})
        )
        if current_tokens + sentence_tokens > chunk_size:
            chunks.append(" ".join(current_sentences))
            current_sentences = [sentence]
            current_tokens = sentence_tokens
        else:
            current_sentences.append(sentence)
            current_tokens += sentence_tokens

    if current_sentences:
        chunks.append(" ".join(current_sentences))
    return chunks
