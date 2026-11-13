# -*- coding: utf-8 -*-
import unittest
from unittest.mock import patch

from cute_tool.tool.search_component import chapter


class DeepSearchChapterStreamTest(unittest.IsolatedAsyncioTestCase):
    def test_non_stream_summary_dead_code_should_be_removed(self):
        self.assertFalse(hasattr(chapter, "summarize_chapter"))

    async def test_stream_summary_should_yield_llm_content(self):
        async def fake_ask_llm(**kwargs):
            for chunk in ("第一段", "", "第二段"):
                yield chunk

        with (
            patch.object(
                chapter,
                "get_prompt",
                return_value={"chapter_summary_prompt": "summary prompt"},
            ),
            patch.object(
                chapter,
                "resolve_openai_compat_env",
                return_value={"api_base": "https://llm.example/v1", "api_key": "key"},
            ),
            patch.object(chapter, "ask_llm", fake_ask_llm),
        ):
            chunks = [
                chunk
                async for chunk in chapter.summarize_chapter_stream(
                    query="测试问题",
                    chapter_title="测试章节",
                    chapter_content="章节说明",
                    chapter_order=1,
                    search_content="检索材料",
                )
            ]

        self.assertEqual(["第一段", "第二段"], chunks)


if __name__ == "__main__":
    unittest.main()
