from __future__ import annotations

from dataclasses import dataclass, field
from typing import Any


@dataclass(frozen=True, slots=True)
class ActionPlaybook:
    action_scope: str
    title: str
    observed: str
    expected: str
    impact: str
    preconditions: tuple[str, ...]
    reproduction_steps: tuple[str, ...]
    expected_failure: str
    root_cause: str
    root_alternatives: tuple[str, ...]
    affected_areas: tuple[dict[str, str], ...]
    fix_plan: tuple[tuple[str, str], ...]
    unit_tests: tuple[str, ...]
    integration_tests: tuple[str, ...]
    regression_tests: tuple[str, ...]
    acceptance_criteria: tuple[str, ...]
    status: str = "ready"
    priority: str = "P1"
    extra: dict[str, Any] = field(default_factory=dict)


def _common_areas(tool_name: str) -> tuple[dict[str, str], ...]:
    return (
        {"kind": "TOOL", "name": tool_name},
        {"kind": "AGENT_LOOP", "name": "工具调用与错误恢复策略"},
        {"kind": "TRACE", "name": "Execution Ledger 工具调用记录"},
    )


def _playbook(
    *,
    action_scope: str,
    title: str,
    observed: str,
    expected: str,
    impact: str,
    preconditions: tuple[str, ...],
    steps: tuple[str, ...],
    expected_failure: str,
    root_cause: str,
    alternatives: tuple[str, ...],
    areas: tuple[dict[str, str], ...],
    fix_plan: tuple[tuple[str, str], ...],
    unit: tuple[str, ...],
    integration: tuple[str, ...],
    regression: tuple[str, ...],
    acceptance: tuple[str, ...],
    status: str = "ready",
    priority: str = "P1",
) -> ActionPlaybook:
    return ActionPlaybook(
        action_scope=action_scope,
        title=title,
        observed=observed,
        expected=expected,
        impact=impact,
        preconditions=preconditions,
        reproduction_steps=steps,
        expected_failure=expected_failure,
        root_cause=root_cause,
        root_alternatives=alternatives,
        affected_areas=areas,
        fix_plan=fix_plan,
        unit_tests=unit,
        integration_tests=integration,
        regression_tests=regression,
        acceptance_criteria=acceptance,
        status=status,
        priority=priority,
    )


def get_playbook(
    failure_kind: str,
    *,
    tool_name: str,
    primary_layer: str,
) -> ActionPlaybook:
    tool = tool_name or "unknown"
    if failure_kind == "ARG_JSON_INVALID":
        return _playbook(
            action_scope="MODEL_POLICY",
            title=f"修复 {tool} 工具入参生成非法 JSON 的问题",
            observed=f"{tool} 收到的入参无法解析为 JSON。",
            expected="模型只能提交可解析、符合 Schema 的 JSON，并在提交前得到明确校验反馈。",
            impact="调用会直接失败，且可能触发重复提交或无效重试。",
            preconditions=("已保存失败调用及其原始 inputJson。",),
            steps=(
                f"使用证据中的原始入参调用 {tool}。",
                "观察参数解析阶段返回的错误。",
                "检查 Agent 是否在未修正参数的情况下继续调用。",
            ),
            expected_failure="JSON 解析失败，工具没有提示具体字段或修正方式。",
            root_cause="参数生成后缺少序列化与 JSON 校验，底层解析异常直接穿透。",
            alternatives=("模型输出被流式截断", "工具适配层错误修改了参数文本"),
            areas=_common_areas(tool),
            fix_plan=(
                ("增加调用前校验", "在工具执行器边界验证 JSON 可解析，并返回字段级错误。"),
                ("补充可操作错误", "错误体至少包含 errorCode、message、field、expected、nextAction。"),
                ("阻止原样重试", "相同参数再次提交前要求重新解析错误并产生参数差异。"),
            ),
            unit=("非法 JSON 返回稳定 ARG_JSON_INVALID", "合法 JSON 不产生回归错误"),
            integration=("模型收到校验错误后能修正参数并完成一次成功调用",),
            regression=("已有合法工具调用仍按原 Schema 执行",),
            acceptance=(
                "非法 JSON 首次失败即可获得明确错误码和修正提示。",
                "相同非法参数不会无变化地连续提交。",
            ),
        )
    if failure_kind == "ARG_REQUIRED_MISSING":
        return _playbook(
            action_scope="MODEL_POLICY",
            title=f"修复 {tool} 工具必填参数缺失的问题",
            observed=f"{tool} 调用缺少工具 Schema 要求的必填字段。",
            expected="调用前完成必填字段检查，缺失时阻止执行并给出字段名。",
            impact="工具无法进入业务逻辑，浪费一次完整调用。",
            preconditions=("工具 Schema 或失败错误中可以识别缺失字段。",),
            steps=(
                f"使用当前证据中的入参调用 {tool}。",
                "检查必填字段校验和返回错误。",
                "补全字段后确认同一任务可以成功。",
            ),
            expected_failure="执行器收到缺少必填字段的调用并失败。",
            root_cause="模型参数生成与工具 Schema 校验之间缺少统一契约检查。",
            alternatives=("工具描述未明确必填字段", "Schema 与实际执行器要求不一致"),
            areas=_common_areas(tool),
            fix_plan=(
                ("统一必填字段校验", "在调用前基于同一份 Schema 校验 required 字段。"),
                ("返回字段级错误", "错误中明确 missingFields，禁止只返回通用失败。"),
                ("同步工具说明", "确保模型可见描述与执行器 Schema 一致。"),
            ),
            unit=("缺少 required 字段返回缺失字段列表", "字段齐全时正常执行"),
            integration=("模型能根据 missingFields 补齐参数后成功调用",),
            regression=("可选字段缺省行为保持不变",),
            acceptance=(
                "首次失败返回明确缺失字段。",
                "补齐参数后无需额外猜测即可成功。",
            ),
        )
    if failure_kind == "ARG_TYPE_MISMATCH":
        return _playbook(
            action_scope="MODEL_POLICY",
            title=f"修复 {tool} 工具参数类型不符合 Schema 的问题",
            observed=f"{tool} 收到的参数类型与工具 Schema 不一致。",
            expected="调用前完成字段类型校验，错误中返回字段名、实际类型和期望类型。",
            impact="请求在参数解析阶段失败，可能诱发连续格式修正调用。",
            preconditions=("工具契约或失败调用中包含可核对字段类型的信息。",),
            steps=(
                f"提交当前错误类型的参数调用 {tool}。",
                "确认执行器返回的字段级类型错误。",
                "改为正确类型后验证调用成功。",
            ),
            expected_failure="类型不符的参数进入执行器并失败。",
            root_cause="模型输出类型与工具 Schema 之间缺少执行前验证。",
            alternatives=("Schema 类型声明错误", "适配层序列化改变原始类型"),
            areas=_common_areas(tool),
            fix_plan=(
                ("执行前类型校验", "按 Schema 校验顶层字段和常用嵌套结构。"),
                ("结构化错误", "返回 field、actualType、expectedType 和修正示例。"),
                ("提示参数示例", "在工具描述中给出符合 Schema 的最小示例。"),
            ),
            unit=("错误类型返回 field/actualType/expectedType", "正确类型正常执行"),
            integration=("Agent 依据类型错误修正参数后成功",),
            regression=("整数与数字、布尔与字符串的边界判定符合 Schema",),
            acceptance=(
                "错误可以定位到具体字段。",
                "错误信息足以指导一次正确修正。",
            ),
        )
    if failure_kind == "ARG_SELECTOR_NOT_FOUND":
        return _playbook(
            action_scope="TOOL_IMPLEMENTATION",
            title=f"修复 {tool} 选择器不存在时错误不可操作的问题",
            observed=f"{tool} 使用了未命中页面元素的选择器，随后可能重复调用。",
            expected="调用前优先观察页面；失败时返回失败 selector、errorCode 和可用元素摘要。",
            impact="导致页面操作失败、重复观察和额外 Token/耗时消耗。",
            preconditions=("目标页面已加载或可稳定复现。", "失败 selector 已保存在 Trace。"),
            steps=(
                f"构造包含失败 selector 的 {tool} 调用。",
                "检查返回值是否包含 ARG_SELECTOR_NOT_FOUND、selector 和候选元素。",
                "使用观察结果中的有效 selector 重试。",
            ),
            expected_failure="工具只返回 selector not found，Agent 无法判断下一步。",
            root_cause="选择器由模型猜测生成，且工具错误缺少可操作上下文。",
            alternatives=("页面在调用前发生变化", "选择器语法合法但元素暂未渲染"),
            areas=_common_areas(tool),
            fix_plan=(
                ("增加元素观察约束", "点击或输入前优先返回可用于生成选择器的元素摘要。"),
                ("稳定错误结构", "返回 errorCode、failedSelector、candidateSelectors、nextAction。"),
                ("防止原样重试", "相同工具与参数失败后，第二次调用前强制重新观察或切换策略。"),
            ),
            unit=("不存在 selector 返回结构化错误", "有效 selector 保持正常点击"),
            integration=("模型根据候选元素修正 selector 后成功完成动作",),
            regression=("CSS/XPath/文本定位现有行为不变",),
            acceptance=(
                "失败 selector 可直接在证据中定位。",
                "错误响应给出至少一种可操作的下一步。",
                "相同 selector 不会无变化地连续调用。",
            ),
        )
    if failure_kind == "ARG_PATH_NOT_FOUND":
        return _playbook(
            action_scope="TOOL_IMPLEMENTATION",
            title=f"修复 {tool} 工作区路径不存在或路径口径不一致的问题",
            observed=f"{tool} 使用了当前工作区不存在的路径。",
            expected="调用前统一相对路径口径，并在失败时返回规范化路径和候选路径。",
            impact="文件读写失败，可能引发跨工具重复尝试或错误删除判断。",
            preconditions=("失败路径和当前工作区根目录可以从 Trace 或任务上下文确定。",),
            steps=(
                f"使用证据中的路径调用 {tool}。",
                "检查返回路径是否经过规范化以及是否列出候选路径。",
                "改用工作区相对路径后验证成功。",
            ),
            expected_failure="工具返回 no such file 或通用路径错误。",
            root_cause="模型参数中的路径口径与工具工作区解析规则不一致。",
            alternatives=("文件确实不存在", "远端沙箱与本地工作区同步缺失"),
            areas=_common_areas(tool),
            fix_plan=(
                ("统一路径规范", "所有文件工具共用工作区相对路径解析器。"),
                ("失败返回候选", "错误中返回规范化路径、工作区根和近似候选。"),
                ("区分离线与同步", "路径不存在但远端存在时标记为同步或环境问题。"),
            ),
            unit=("不存在路径返回 ARG_PATH_NOT_FOUND", "相对路径和合法绝对路径按约定解析"),
            integration=("路径错误在同步缺口场景下不会误归因给模型",),
            regression=("已有文件读写路径解析保持一致",),
            acceptance=(
                "错误中能看到实际解析后的路径。",
                "Agent 能通过候选路径或工作区根信息修正调用。",
            ),
        )
    if failure_kind == "ARG_URL_INVALID":
        return _playbook(
            action_scope="TOOL_IMPLEMENTATION",
            title=f"修复 {tool} URL 或协议参数校验不可诊断的问题",
            observed=f"{tool} 收到不合法 URL 或协议并返回底层错误。",
            expected="Schema 和运行时同时校验 URL，错误中返回字段名、非法值摘要和允许协议。",
            impact="调用在参数阶段失败，底层异常可能误导模型继续尝试。",
            preconditions=("失败 URL 或协议已保存在工具入参。",),
            steps=(
                f"使用证据中的非法 URL 调用 {tool}。",
                "确认错误是否指出字段、协议和合法格式。",
                "改为 http/https 合法 URL 后验证成功。",
            ),
            expected_failure="底层抛出 URL scheme 或解析异常，缺少可操作提示。",
            root_cause="URL 校验依赖底层库异常，工具契约没有统一格式约束。",
            alternatives=("调用方传入了空字符串", "相对 URL 需要在特定上下文中解析"),
            areas=_common_areas(tool),
            fix_plan=(
                ("增加 URL Schema", "约束必填字段为绝对 http/https URL。"),
                ("预校验并归一化", "调用前解析 URL，拒绝无协议或非法主机。"),
                ("结构化错误", "返回 field、invalidValue、allowedSchemes、nextAction。"),
            ),
            unit=("无协议 URL 返回 ARG_URL_INVALID", "合法 http/https URL 正常执行"),
            integration=("模型根据错误修正协议后成功调用",),
            regression=("URL 查询参数和编码保持原样",),
            acceptance=(
                "错误中明确说明缺少或不允许的协议。",
                "合法 URL 行为不受影响。",
            ),
        )
    if failure_kind == "ARG_REPEATED_AFTER_FAILURE":
        return _playbook(
            action_scope="PRODUCT_RUNTIME",
            title=f"修复 {tool} 失败后重复提交相同参数的问题",
            observed=f"{tool} 在失败后再次提交了完全相同的工具和参数。",
            expected="失败后先读取错误并改变参数、工具或策略，禁止无变化重试。",
            impact="浪费调用次数、Token 和耗时，并掩盖真正失败原因。",
            preconditions=("连续失败调用的 canonical input 完全一致。",),
            steps=(
                f"连续两次提交相同参数的 {tool} 调用。",
                "观察第二次调用前是否读取上一次错误。",
                "修改参数或切换工具后验证任务可继续。",
            ),
            expected_failure="第二次调用与第一次完全相同，错误没有变化。",
            root_cause="Agent Loop 没有保存最近失败签名，也没有策略切换门槛。",
            alternatives=("工具本身存在瞬时故障", "模型上下文未包含上次错误"),
            areas=(
                {"kind": "AGENT_LOOP", "name": "失败调用与重试策略"},
                {"kind": "TOOL", "name": tool},
            ),
            fix_plan=(
                ("记录失败签名", "保存工具名、参数 canonical hash、错误签名和调用 ID。"),
                ("增加预调用门禁", "相同签名再次出现时先注入诊断提示并要求修改。"),
                ("触发策略切换", "连续相同失败达到阈值后终止该子路径或改换工具。"),
            ),
            unit=("相同参数第二次调用被门禁识别", "参数变化或工具变化允许继续"),
            integration=("错误注入后 Agent 能切换策略并完成任务",),
            regression=("正常的幂等重试不会误伤",),
            acceptance=(
                "相同失败参数不会无变化地连续调用超过一次。",
                "失败签名和阻断原因可在 Trace 中看到。",
            ),
        )
    if failure_kind == "FAIL_TIMEOUT":
        return _playbook(
            action_scope="TOOL_IMPLEMENTATION",
            title=f"修复 {tool} 超时后状态与重试策略不稳定的问题",
            observed=f"{tool} 调用未在运行时预算内完成。",
            expected="每个工具拥有明确超时、取消和稳定 TIMEOUT 状态，非幂等操作不得自动重试。",
            impact="阻塞后续步骤，可能造成重复执行或不确定副作用。",
            preconditions=("存在超时状态和耗时证据。",),
            steps=(
                f"使用证据中的参数触发 {tool} 超时。",
                "检查超时状态、取消信号和最终 Trace 是否一致。",
                "仅对幂等操作验证受控重试。",
            ),
            expected_failure="调用超时但状态、结果或取消信息不完整。",
            root_cause="工具超时边界与执行器取消/持久化逻辑没有统一。",
            alternatives=("下游服务本身过慢", "预算设置低于正常任务耗时"),
            areas=_common_areas(tool),
            fix_plan=(
                ("配置独立超时", "在工具执行器声明 timeoutMs 并传递取消信号。"),
                ("持久化最终状态", "finally 路径写入 TIMEOUT、finishedAt 和错误摘要。"),
                ("限制重试", "只允许明确幂等的工具进行受控重试。"),
            ),
            unit=("超时触发取消并写入 TIMEOUT", "超时后不会写 SUCCESS"),
            integration=("非幂等工具超时不会自动重复执行",),
            regression=("正常耗时调用不受超时配置影响",),
            acceptance=(
                "超时状态和耗时稳定可见。",
                "超时后没有不确定的自动重试。",
            ),
            priority="P0",
        )
    if failure_kind == "FAIL_NETWORK":
        return _playbook(
            action_scope="ENVIRONMENT_CONFIG",
            title=f"修复 {tool} 网络失败被误当作 Agent 能力问题的情况",
            observed=f"{tool} 调用出现 DNS、连接或网络中断错误。",
            expected="环境错误被稳定分类，Agent 获得可操作的重连、代理或稍后重试提示。",
            impact="错误归因会污染模型能力判断，并触发无效参数重试。",
            preconditions=("错误文本包含网络、DNS、连接或代理信息。",),
            steps=(
                f"在受控网络条件变化下调用 {tool}。",
                "检查错误是否被归为 ENVIRONMENT。",
                "恢复网络后验证同一调用可以成功。",
            ),
            expected_failure="Agent 将网络错误误判为参数错误并重复调用。",
            root_cause="环境错误分类与健康检查没有进入 Agent 决策上下文。",
            alternatives=("目标站点不可达", "代理或登录态配置错误"),
            areas=_common_areas(tool),
            fix_plan=(
                ("稳定网络分类", "识别 DNS、连接拒绝、超时和代理错误并返回环境错误码。"),
                ("增加健康提示", "错误中给出检查网络、代理或稍后重试的 nextAction。"),
                ("阻止参数化重试", "网络错误下禁止仅修改无关参数后立即重试。"),
            ),
            unit=("DNS 错误映射为 FAIL_NETWORK", "普通参数错误不会被误分类"),
            integration=("网络恢复后 Agent 可继续任务",),
            regression=("登录/权限错误仍归为 FAIL_AUTH",),
            acceptance=(
                "网络问题不会被归因给模型参数。",
                "Trace 中保留原始网络错误摘要。",
            ),
            priority="P0",
        )
    if failure_kind == "FAIL_AUTH":
        return _playbook(
            action_scope="ENVIRONMENT_CONFIG",
            title=f"修复 {tool} 登录态或权限失效缺少可操作诊断的问题",
            observed=f"{tool} 调用出现未登录、Token 失效或权限不足错误。",
            expected="认证失败被稳定识别，并返回重新登录或请求权限的明确动作。",
            impact="Agent 可能重试相同调用，无法恢复任务。",
            preconditions=("错误文本包含认证或权限信号。",),
            steps=(
                f"在失效登录态下调用 {tool}。",
                "确认错误被分类为 FAIL_AUTH。",
                "恢复登录态后验证成功。",
            ),
            expected_failure="工具返回通用失败，Agent 继续使用相同凭证。",
            root_cause="认证状态检测和错误码未进入统一工具错误契约。",
            alternatives=("账号权限范围不足", "会话 Cookie 过期"),
            areas=_common_areas(tool),
            fix_plan=(
                ("统一认证错误码", "返回 FAIL_AUTH 和 authState。"),
                ("可操作提示", "提示重新登录、刷新凭证或申请权限。"),
                ("停止无意义重试", "认证失败时阻止相同参数自动重试。"),
            ),
            unit=("401/403 映射为 FAIL_AUTH", "普通 500 不映射为认证错误"),
            integration=("重新登录后工具恢复调用",),
            regression=("匿名可访问工具不强制登录",),
            acceptance=(
                "认证问题与参数问题可区分。",
                "Agent 获得可执行的恢复动作。",
            ),
            priority="P0",
        )
    if failure_kind == "FAIL_EXECUTOR_EXCEPTION":
        return _playbook(
            action_scope="PRODUCT_RUNTIME",
            title=f"修复 {tool} 执行器内部异常穿透且状态可能误报的问题",
            observed=f"{tool} 抛出未归一化的执行器异常。",
            expected="执行器边界捕获异常、保留 cause、返回稳定错误码，并保证 Ledger 写入失败状态。",
            impact="可能丢失结果、误报成功或让 Agent 收到不可读异常。",
            preconditions=("Trace 中存在执行器异常文本或失败状态。",),
            steps=(
                f"使用证据中的参数触发 {tool} 执行器异常。",
                "检查错误码、cause 和 Ledger 最终状态。",
                "确认失败不会写入 SUCCESS 或空结果。",
            ),
            expected_failure="底层异常直接穿透，错误体不可操作。",
            root_cause="执行器边界缺少统一异常映射和 finally 持久化。",
            alternatives=("下游依赖返回异常", "序列化阶段丢失结果"),
            areas=_common_areas(tool),
            fix_plan=(
                ("边界捕获异常", "在执行器入口统一捕获并映射 errorCode。"),
                ("保留 cause", "日志和 Trace 保存异常类型与脱敏后的 cause。"),
                ("保证一致性", "同一事务或 finally 路径写入 status/result/error/finishedAt。"),
            ),
            unit=("执行器异常返回稳定 errorCode", "异常后 Ledger 不保留 RUNNING/SUCCESS"),
            integration=("异常被记录后 Agent 能识别为运行时失败",),
            regression=("正常结果序列化不变",),
            acceptance=(
                "异常不会被吞成空结果。",
                "失败状态、错误和结束时间同时存在。",
            ),
            priority="P0",
        )
    if failure_kind == "FAIL_RESULT_MISSING":
        return _playbook(
            action_scope="TRACE_INSTRUMENTATION",
            title=f"修复 {tool} 调用结果和错误同时缺失的 Trace 缺口",
            observed=f"{tool} 在 Trace 中没有结果、观察文本或错误消息。",
            expected="每次工具执行都必须写入 status、result/error、statusSource 和 finishedAt。",
            impact="无法判断是工具失败、模型决策失败还是基础设施丢失。",
            preconditions=("失败证据中 resultPresent=false 且 errorMsg 为空。",),
            steps=(
                f"执行一次会产生明确结果的 {tool} 调用。",
                "查询 Execution Ledger 的 tool invocation 记录。",
                "检查成功和异常路径是否都写入最终结果。",
            ),
            expected_failure="有调用记录但没有结果、错误或完成时间。",
            root_cause="结果写入与状态写入不在同一完成路径，异常分支遗漏持久化。",
            alternatives=("读模型没有投影结果字段", "结果被后续步骤覆盖"),
            areas=_common_areas(tool),
            fix_plan=(
                ("同一事务写入", "统一写入 status/result/error/statusSource/finishedAt。"),
                ("补齐异常路径", "在 finally 中保证至少写入失败状态和原因。"),
                ("增加完整性检查", "导出 Trace 时标记 resultPresent 和 statusSource。"),
            ),
            unit=("无结果但有状态会被识别为 TRACE_GAP", "成功结果完整写入"),
            integration=("执行器异常后仍能在 Ledger 读取失败原因",),
            regression=("历史有结果记录仍可正常导出",),
            acceptance=(
                "不存在只有状态没有结果/错误的已完成调用。",
                "Trace 能明确标记结果来源。",
            ),
            status="needs_more_evidence",
            priority="P0",
        )
    if failure_kind == "FAIL_SCHEMA_CONTRACT":
        return _playbook(
            action_scope="TOOL_IMPLEMENTATION",
            title=f"修复 {tool} 工具错误不可操作的问题",
            observed=f"{tool} 返回了 unknown error、operation failed 或其他通用错误。",
            expected="所有可预期失败返回稳定 errorCode、字段和 nextAction，避免通用错误。",
            impact="Agent 无法根据错误调整策略，容易重复调用。",
            preconditions=("失败调用包含通用错误文本。",),
            steps=(
                f"使用证据中的输入触发 {tool} 失败。",
                "检查错误结构是否包含 errorCode、field、expected 和 nextAction。",
                "根据 nextAction 验证可恢复路径。",
            ),
            expected_failure="错误只有自然语言，无法稳定解析。",
            root_cause="工具错误契约不统一，底层失败被折叠成通用文案。",
            alternatives=("异常被上层 catch-all 覆盖", "旧版执行器未实现新错误结构"),
            areas=_common_areas(tool),
            fix_plan=(
                ("定义统一错误体", "实现 ok/errorCode/message/field/expected/nextAction。"),
                ("映射底层错误", "把常见异常映射为稳定错误码。"),
                ("测试序列化", "覆盖成功、参数错误、运行时错误和环境错误。"),
            ),
            unit=("通用异常映射为稳定错误码", "错误体字段完整"),
            integration=("Agent 可依据 nextAction 恢复",),
            regression=("成功响应结构保持兼容",),
            acceptance=(
                "不再只返回 unknown error 或 operation failed。",
                "错误码在同类失败中保持稳定。",
            ),
        )
    if failure_kind == "FAIL_USER_STOP":
        return _playbook(
            action_scope="NO_ACTION",
            title=f"{tool} 调用由用户主动停止",
            observed="用户或上层流程主动停止了工具调用。",
            expected="保留停止原因，不将用户操作误判为模型或工具缺陷。",
            impact="无需代码修复，但应避免在后续统计中当作能力失败。",
            preconditions=("Trace 或状态明确记录用户停止。",),
            steps=("确认停止来源为 USER_ACTION。", "检查停止后没有继续执行未授权操作。"),
            expected_failure="不适用。",
            root_cause="用户主动终止，不是 Agent 故障。",
            alternatives=(),
            areas=({"kind": "TRACE", "name": "用户停止原因"},),
            fix_plan=(
                ("保持分类", "统计和报告中继续标记为用户操作。"),
                ("核对副作用", "确认停止后没有继续执行危险动作。"),
            ),
            unit=("用户停止不生成运行时修复任务",),
            integration=("停止后的 Trace 仍完整保留",),
            regression=("真实失败仍进入故障归因",),
            acceptance=("用户操作不会污染模型能力统计。",),
            priority="P2",
        )
    return _playbook(
        action_scope="TRACE_INSTRUMENTATION",
        title=f"补齐 {tool} 失败证据后再定位责任层",
        observed=f"{tool} 的失败当前只能归因为 {primary_layer}，证据不足以给出确定修复。",
        expected="先补齐入参、结果、错误、状态、时间和产物关联，再生成针对性修复。",
        impact="直接修复可能误改模型、工具或环境层。",
        preconditions=("当前归因层为 UNKNOWN 或规则覆盖不足。",),
        steps=(
            f"重新执行同类 {tool} 任务并保留完整 Trace。",
            "检查工具入参、结果、错误、状态和时间是否齐全。",
            "证据补齐后重新运行确定性归因。",
        ),
        expected_failure="再次出现无法定位责任层的失败。",
        root_cause="当前证据不足，不能可靠区分模型、Harness 或环境。",
        alternatives=("存在尚未覆盖的错误短语", "失败发生在 Trace 记录之外"),
        areas=_common_areas(tool),
        fix_plan=(
            ("补齐 Trace", "确保工具调用各阶段字段完整可读。"),
            ("扩展规则", "将新发现的可复现错误短语加入确定性归因规则。"),
        ),
        unit=("缺失字段会标记 needs_more_evidence",),
        integration=("补齐证据后可命中明确 Playbook",),
        regression=("UNKNOWN 不会被硬猜成 P0 修复",),
        acceptance=(
            "行动项明确列出缺失证据。",
            "证据不足时不会伪造文件路径或根因。",
        ),
        status="needs_more_evidence",
        priority="P1",
    )
