"""Official MemoryAgentBench query templates.

Copied from the official agentic-memory query templates in
`utils/templates.py` at the pinned upstream revision in manifest.yaml.
"""

from __future__ import annotations


def query_for_source(
    source: str,
    question: str,
    agent_style: str = "agentic_memory_agent",
) -> str:
    if agent_style != "agentic_memory_agent":
        raise ValueError(
            f"unsupported MemoryAgentBench agent style: {agent_style}"
        )
    if source.startswith("eventqa_"):
        template = (
            "Search Archival Memory, complete the task below:\n\n"
            "{question}\n\n The event that happens next is:"
        )
        return template.format(question=question)
    if source.startswith("ruler_qa"):
        template = (
            "Search Archival Memory and answer my question. Only give me the "
            "answer and do not output any other words. \n\nQuestion: {question}"
            " \n\n Answer:"
        )
        return template.format(question=question)
    if source.startswith("longmemeval_"):
        template = (
            "Search Archival Memory and answer the question as concisely as you "
            "can, using a single phrase if possible.\n\n {question} \n\n Answer:"
        )
        return template.format(question=question)
    if source.startswith("icl_"):
        template = (
            "Search Archival Memory and use the provided mapping from the "
            "context to numerical label to assign a numerical label to the "
            'context. Only output "label: {{label}}" and nothing else. '
            "\n\n{question} \n\n label:"
        )
        return template.format(question=question)
    if source.startswith("detective_"):
        template = (
            "Search Archival Memory and answer the question below. You are "
            "required to answer the question based on the strict output "
            "format.\n\n {question} \n\n"
        )
        return template.format(question=question)
    if source.startswith("factconsolidation_"):
        template = (
            "Pretend you are a knowledge management system. Each fact in the  "
            "Archival Memory is provided with a serial number at the "
            "beginning, and the newer fact has larger serial number. \n You "
            "need to solve the conflicts of facts in the Archival Memory by "
            "finding the newest fact with larger serial number. You need to "
            "answer a question based on this rule. You should give a very "
            "concise answer without saying other words for the question "
            "**only** from the knowledge pool you have memorized rather than "
            "the real facts in real world. \n\nFor example:\n\n [Archival "
            "Memory] \n\n Question: Based on the Archival Memory, what is the "
            "name of the current president of Russia? \nAnswer: Donald Trump "
            "\n\n Now Answer the Question: Based on the  Archival Memory, "
            "{question} \nAnswer:"
        )
        return template.format(question=question)
    if source.startswith("infbench_"):
        template = (
            "You are given a book above and you are tasked to summarize it. "
            "\n\n{question} \n\n Now summarize the book."
        )
        return template.format(question=question)
    if source.startswith("recsys_"):
        template = (
            "Pretend you are a movie recommender system. You need to recommend "
            "movies based on the dialogues you have memorized. Now I will give "
            "you a new conversation between a user and you (a recommender "
            "system). Search Archival Memory, you reply me with 20 "
            "recommendations without extra sentences. \n\nFor Example:\n\n"
            "[Conversation]\n\nThe recommendations are: \n1.movie1\n2.movie2"
            "\n...\n\n Here is the conversation: {question} \n\n The "
            "recommendations are: \n"
        )
        return template.format(question=question)
    raise ValueError(f"unsupported MemoryAgentBench source: {source}")
