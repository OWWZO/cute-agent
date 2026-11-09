from cute_tool.tool.search_component.chapter import strip_appended_refusal


def test_strips_refusal_appended_after_complete_chapter():
    report = "# 报告\n\n" + ("有效内容。" * 200)
    refusal = "抱歉，系统检测到您当前输入的信息存在敏感内容，我无法响应您的请求，请检查后重新输入。"

    assert strip_appended_refusal(report + "\n\n" + refusal) == report


def test_keeps_short_refusal_only_answer():
    refusal = "抱歉，系统检测到您当前输入的信息存在敏感内容，我无法响应您的请求。"

    assert strip_appended_refusal(refusal) == refusal


def test_keeps_refusal_marker_inside_long_report():
    report = (
        "# 报告\n\n"
        + ("有效内容。" * 100)
        + "这里讨论系统为何会提示敏感内容。"
        + ("后续内容。" * 100)
    )

    assert strip_appended_refusal(report) == report.strip()
