"""
Embedding 模块：文本稠密向量、图片多模态向量；BM25 从 ``bm25_embedding`` 子模块直接导入。
"""

from .embedding import BaseEmbedding, ImageEmbedding, TextEmbedding

# 保留原有 eager import 的模块级副作用；公共类直接从定义模块导出。
from . import image_embedding as _image_embedding  # noqa: F401
from . import text_embedding as _text_embedding  # noqa: F401

__all__ = ["BaseEmbedding", "TextEmbedding", "ImageEmbedding"]
