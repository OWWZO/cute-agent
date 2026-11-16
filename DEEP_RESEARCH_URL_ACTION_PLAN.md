# DeepSearch URL Research Action 设计与落地计划

> 状态：阶段 A-F 已实施并通过确定性回归、URL 实时冒烟与 Java 编译
> 日期：2026-11-14
> 适用项目：`D:\Java Code\ai-agent\Cute-agent`
> 主要范围：Python `cute-tool` 的 DeepSearch 章节研究与迭代编辑流程
> 相关模块：`cute-tool/cute_tool/tool/deepsearch.py`、`search_component/`、`web_fetcher.py`

## 0. 一句话结论

不新增 `seedUrls` 入参。用户在自然语言 `query` 中直接写 URL，DeepSearch 内部的章节规划模型和 Reviewer 把 URL 识别成 `web_fetch` 研究动作，使其与 `web_search` 同级；后端负责 URL 校验、去重、抓取、正文提取、链接发现、上下文限流和来源追踪。

最终形态不是开放式爬虫，而是：

```text
用户 query
  -> 研究动作规划
  -> 精确 URL 抓取 / 关键词搜索
  -> 章节总结
  -> Reviewer 找缺口
  -> 选择搜索词或页面链接
  -> Editor 修改当前总结
  -> 有界重复
  -> 最终报告
```

## 1. 当前实现的事实

### 1.1 当前请求与章节规划

- Python `DeepSearchRequest` 目前包含 `query`、`max_loop`、`search_engines` 等字段，没有 URL 字段。
- 外层 Java `DeepSearchTool` 目前只向模型暴露 `query` 和 `reportFileName`。
- `query_decompose()` 当前只生成章节的 `title`、`content`、`search_queries`。
- `ChapterState.queries` 只承载搜索关键词。

### 1.2 当前章节执行

- `_research_one_chapter()` 先执行章节搜索词。
- 搜索结果通过 `docs_to_html()` 进入章节总结模型。
- 有迭代编辑开关时，进入 `_research_iteratively()`。
- Reviewer 当前只能生成 `web_search`，`SearchAction` 的 `type` 和 `tool` 会被归一化成搜索。
- Editor 只能拿到本轮新证据和当前总结，不会执行 URL 抓取。

### 1.3 当前抓取能力

- `WebFetcher` 已具备 `aiohttp` 下载、`trafilatura` 正文提取和 `BeautifulSoup` 兜底能力。
- `/v1/tool/web_fetch` 是独立端点。
- DeepSearch 目前不调用 `WebFetcher`，而是使用 Exa Search 返回的 `text` / `extract`。
- `WebFetcher` 目前没有向 DeepSearch 暴露结构化的页面链接候选。

### 1.4 当前缺口

当前系统无法完成以下闭环：

1. 从用户 query 中识别 URL。
2. 精确抓取 URL，而不是依赖搜索 API 找回同一页面。
3. 把 URL 正文和关键词搜索结果一起生成章节总结。
4. 从已抓页面提取链接候选。
5. 让 Reviewer 选择具体链接继续下钻。
6. 把下钻结果以编辑操作回填当前总结。

## 2. 设计决策

### 2.1 不增加公共 `seedUrls` 字段

用户仍然使用自然语言：

```text
根据 https://example.com/news/123 和
https://example.com/background/456，
研究某公司最近的监管风险，并继续查文中提到的官方公告。
```

外层 Agent 继续调用原来的 `deep_search`，由 DeepSearch 内部的规划模型识别 URL。

这样做的好处：

- 不扩大 Java、Python 公共接口。
- URL 和搜索词共享同一套研究动作协议。
- 用户可以在同一句话里混合主题、URL 和研究要求。
- 后续页面链接下钻不需要额外接口。

### 2.2 URL 是 ResearchAction，不是特殊参数

统一的动作模型：

```json
{
  "type": "search",
  "tool": "web_search",
  "query": "公司 监管 调查 官方回应",
  "reason": "补充公开搜索结果",
  "gap_ids": ["G1"]
}
```

```json
{
  "type": "fetch_url",
  "tool": "web_fetch",
  "url": "https://example.com/company/announcement",
  "reason": "抓取公司公告原文",
  "gap_ids": ["G1"]
}
```

模型负责“选择动作”，后端负责“执行动作”。

### 2.3 URL 由模型输出，但不能完全相信模型

执行前必须做确定性校验：

- URL 必须是 `http` 或 `https`。
- URL 必须来自用户原始 query、当前已抓页面候选或已确认的来源池。
- 统一移除 fragment、尾斜杠和重复项。
- 拦截私网、回环地址、云元数据地址和危险重定向。
- 同一个 URL 在一个 DeepSearch 运行中只抓取一次。

模型可以决定“是否值得抓”，但不能决定“抓任意地址”。

### 2.4 首版范围

首版只支持普通 HTML、Markdown 和纯文本页面。

不在首版范围：

- 需要登录态的页面。
- PDF/MinerU 解析链路。
- 强 JavaScript 渲染页面。
- 自动发现整个站点的全部链接并全量抓取。
- 用浏览器代替 `WebFetcher` 做开放世界爬取。

PDF、JS 页面和浏览器兜底另开阶段处理。

## 3. 目标流程

### 3.1 端到端流程

```text
用户 query
  |
  v
DeepSearch 接收 query（不新增 URL 参数）
  |
  +--> 后端确定性扫描 query 中的 URL
  |
  v
章节规划模型
  - 看到完整 query
  - 拆章节
  - 为每章输出 ResearchAction[]
       - web_search(query)
       - web_fetch(url)
  |
  v
动作校验与去重
  - URL 安全校验
  - 搜索词去重
  - URL -> 运行级抓取缓存
  |
  v
并发执行首轮动作
  - 搜索 -> MixSearch
  - URL -> WebFetcher
  |
  v
文档合并与裁剪
  - 正文、标题、最终 URL
  - 页面链接候选
  - 统一来源账本
  |
  v
章节初始总结 S0
  |
  v
Reviewer
  - 当前 summary
  - 未解决 gaps
  - 已抓 URL
  - 链接候选
  - 已搜 query
  - 已使用/已丢弃来源
  |
  +--> sufficient=true：结束本章
  |
  +--> 输出 ResearchAction[]
          - 新搜索词
          - 具体 URL
  |
  v
执行选中的动作
  |
  v
新增证据包（有长度上限）
  |
  v
Editor
  - old_text / new_text
  - 修改当前 summary
  |
  v
下一轮 Reviewer
  |
  v
达到 sufficient / 无新证据 / 最大轮数
  |
  v
章节拼接为最终 Markdown 报告
```

### 3.2 章节内的具体行为

首轮：

1. 章节规划模型生成初始动作。
2. `web_search` 和 `web_fetch` 可以并发执行。
3. 搜索和抓取结果合并、去重。
4. 生成章节总结 `S0`。

后续轮次：

1. Reviewer 读取最新 summary，不读取旧的完整报告。
2. Reviewer 只看到经过预算限制的来源索引和候选链接。
3. Reviewer 生成搜索词或选择候选 URL。
4. 执行器抓取或搜索。
5. 新文档进入 `SourceLedger`。
6. Editor 用新增证据修改当前 summary。
7. 新页面中的链接被提取，作为下一轮候选。

### 3.3 下钻不是无限爬虫

下钻必须满足所有条件：

- 有明确的未解决 gap。
- URL 来自已抓页面的候选链接或用户原始 query。
- 当前深度小于 `max_url_depth`。
- 当前抓取数量小于 `max_url_fetches`。
- URL 没有在本次运行中抓取过。
- URL 通过安全检查。
- Reviewer 的当前动作数量没有超过 `max_actions_per_round`。

## 4. 动作协议

### 4.1 新增 ResearchAction

计划新增或重命名 `SearchAction` 为 `ResearchAction`：

```python
class ResearchAction(BaseModel):
    type: Literal["search", "fetch_url"]
    tool: Literal["web_search", "web_fetch"]
    query: str = ""
    url: str = ""
    reason: str = ""
    gap_ids: list[str] = Field(default_factory=list)
```

校验规则：

- `type=search` 时，`tool` 必须为 `web_search`，`query` 非空。
- `type=fetch_url` 时，`tool` 必须为 `web_fetch`，`url` 非空。
- 模型给错枚举时只做已知别名归一化，不允许静默变成另一种动作。
- `gap_ids` 只能引用当前 gaps。
- 初始章节动作可以没有 `gap_ids`。

### 4.2 章节规划输出

建议将 `chapter_structure_prompt` 的输出升级为：

```json
[
  {
    "title": "监管风险与官方回应",
    "content": "核实监管文件、公司公告和最新回应",
    "actions": [
      {
        "type": "search",
        "tool": "web_search",
        "query": "公司 监管 调查 官方公告"
      },
      {
        "type": "fetch_url",
        "tool": "web_fetch",
        "url": "https://example.com/company/announcement"
      }
    ]
  }
]
```

为兼容旧输出，解析器必须同时支持：

- 旧字段 `search_queries`：自动转换成多个 `search` 动作。
- 新字段 `actions`：原样解析。
- 章节只有标题、没有动作时，使用章节 `content` 生成兜底搜索动作。

### 4.3 Reviewer 输出

Reviewer 继续使用 `ReviewResult.actions`，但元素类型改为 `ResearchAction`。

Reviewer 上下文中增加：

```json
{
  "link_candidates": [
    {
      "url": "https://example.com/regulator/file-001",
      "title": "监管文件",
      "anchor": "查看监管文件",
      "parent_url": "https://example.com/news/123",
      "depth": 0
    }
  ]
}
```

Prompt 必须明确：

- 只能从 `link_candidates` 中选择 `fetch_url`。
- 不得发明 URL。
- 已有 summary 中已经出现过的事实，不因为“还有更多网页”而继续抓取。
- 没有足够证据支持时必须写明具体 gap，而不是泛泛说“需要更多资料”。

## 5. 数据模型与存储

### 5.1 Doc 扩展

URL 抓取结果继续复用 `Doc`，在 `Doc.data` 中增加：

```text
origin=seed | search | link
depth=0..N
original_url=用户输入的原始 URL
final_url=重定向后的最终 URL
parent_url=链接来源页面
link_candidates=页面发现的链接元数据
```

`Doc.content` 保存经 `WebFetcher` 提取的正文，不作为原始 HTML 保存。

### 5.2 SourceRecord 扩展

`SourceRecord` 增加：

```text
origin
depth
parent_url
original_url
```

内部 `source_id` 继续保留，仅用于后端去重、追踪和统计，不进入模型上下文。

### 5.3 运行级 URL 缓存

新增运行级 `UrlFetchCache` 或等价组件：

```text
normalize_url(url) -> Doc | failure
```

作用：

- 多个章节识别到同一个 URL 时只发一次网络请求。
- 同一个 URL 成功抓取后，后续章节直接复用 `Doc`。
- 失败结果也缓存，避免重复失败。
- 页面链接候选和正文都挂在同一个 `Doc` 上。

### 5.4 跨章节来源关系

每章保留自己的 `SourceLedger`，用于记录本章实际引用了哪些来源。

运行级缓存只负责网络抓取和页面内容复用，不把其它章节的完整正文无条件塞入当前章节。

## 6. URL 抓取与链接发现

### 6.1 URL 执行器

DeepSearch 内部直接调用 `WebFetcher`，不通过 HTTP 自调用 `/web_fetch`：

```text
ResearchAction(type=fetch_url)
  -> UrlSafetyValidator
  -> UrlFetchCache
  -> WebFetcher.fetch()
  -> Doc
  -> SourceLedger
```

这样可以避免服务自身 HTTP 往返，也便于复用测试和缓存。

### 6.2 页面链接提取

`WebFetcher` 的 HTML 分支需要从原始 HTML 中提取：

```python
LinkCandidate(
    url=absolute_url,
    title=...,
    anchor=...,
    parent_url=final_url,
    depth=parent_depth + 1,
)
```

过滤规则：

- 只保留 `http` / `https`。
- 去掉 fragment。
- 去掉 `mailto:`、`javascript:`、`file:`、空链接。
- 跳过明显的静态资源链接。
- 同一个页面内按 URL 去重。
- 单页候选数量有上限。
- 优先保留正文附近的链接和来源页面上的链接。

首版不做语义排序，只记录 `anchor`、标题和父页面；由 Reviewer 选择。

### 6.3 URL 深度

首版默认：

- 用户 URL 深度为 `0`。
- 从用户 URL 页面发现的链接深度为 `1`。
- 首版最多下钻到深度 `1`。
- 通过配置预留深度 `2`，但默认不开启。

## 7. 执行器与章节改造

### 7.1 ChapterState

增加：

```text
actions: list[ResearchAction]
link_candidates: list[LinkCandidate]
url_fetch_count: int
```

`queries` 可以保留为兼容字段，但不再作为唯一执行入口。

### 7.2 初始章节执行

将当前：

```text
queries -> search -> docs -> summary
```

改为：

```text
actions
  -> search tasks
  -> URL fetch tasks
  -> merge docs
  -> docs_to_html
  -> summary
```

执行要求：

- 搜索和 URL 抓取分别设置并发上限。
- 单个动作失败不终止整章。
- 所有成功文档统一去重。
- 失败动作记录日志和状态，不把失败正文写入模型上下文。

### 7.3 迭代章节执行

复用现有 `_research_iteratively()`，但：

- `ReviewResult.actions` 解析成 `ResearchAction[]`。
- `actions` 中的 `search` 走现有搜索路径。
- `actions` 中的 `fetch_url` 走新 URL 执行器。
- 两类动作产生的新 `Doc` 统一进入 `SourceLedger.add_documents()`。
- `build_evidence_context()` 继续负责最终证据包的大小限制。
- Editor、SourceLedger、URL 反查来源的逻辑保持不变。

### 7.4 已有 URL 的处理

Reviewer 或章节规划模型再次提出已抓 URL 时：

- 直接从缓存拿 `Doc`。
- 不重复请求网页。
- 如果该 URL 已经进入当前 summary，视为已使用。
- 如果只是链接候选且没有实际引用，标记为未使用或丢弃。

## 8. 上下文与缓存策略

### 8.1 模型上下文

建议沿用当前预算：

- 单来源正文最多约 `4000` 字符。
- 每轮证据包总量最多约 `16000` 字符。
- 页面链接候选只放元数据。
- 章节初始资料通过现有模型上下文裁剪逻辑限制。
- 下一轮 Reviewer 只接收最新 summary，不重复灌入旧 summary 全文。

### 8.2 Prompt 缓存

- Reviewer 和 Editor 在同一轮共享稳定消息前缀。
- 搜索结果和 URL 抓取结果作为同一轮的追加证据。
- 下一轮重新构建上下文，只带最新 summary、未解决 gap 和新的候选来源。
- 不把旧 Report、旧 Gap 和新 Gap 同时作为权威状态。

### 8.3 来源追踪

- 页面正文按 URL 去重。
- 内容重复但 URL 不同的页面按内容 hash 去重。
- 内部 `source_id` 不暴露给模型。
- 最终正文使用 `[[编号]](原始URL)` 形式引用。

## 9. 安全与失败处理

### 9.1 SSRF 防护

必须有确定性 URL 安全检查：

- 只允许 `http` / `https`。
- 解析 DNS 后拦截私网、回环、链路本地和保留地址。
- 检查重定向后的最终地址。
- 禁止访问云元数据地址。
- 限制重定向次数。
- 如未来支持内网 URL，必须使用显式 allowlist，不能默认放开。

### 9.2 内容限制

- 限制单页 HTTP 大小。
- 限制正文提取后的字符数。
- 限制并发请求数。
- 限制每章和总运行的 URL 抓取数量。
- 只接受 HTML、Markdown、纯文本；其他类型返回明确失败。

### 9.3 失败策略

以下情况不能让整个 DeepSearch 失败：

- 单个 URL 超时。
- 单个 URL 403/404。
- 单个页面正文为空。
- 页面内容类型不支持。
- 链接候选中没有可用链接。

失败动作记录：

```text
URL_FETCH_FAILED
URL_REDIRECT_REJECTED
URL_UNSUPPORTED_CONTENT
URL_LIMIT_REACHED
```

如果用户提供的 URL 全部失败，DeepSearch 仍继续基于关键词搜索完成报告，并在内部追踪中记录失败来源。

## 10. 分阶段实施

### 阶段 A：动作协议与向后兼容

工作：

- 新增 `ResearchAction`，保留旧 `search_queries` 解析兼容。
- 扩展 `ReviewResult.actions` 的类型。
- 增加动作校验和 URL/query 互斥校验。
- 增加动作序列化、解析和错误处理测试。

验证：

- 旧的章节规划输出仍能生成搜索动作。
- 新输出能生成 `web_search` 和 `web_fetch`。
- 错误的 type/tool/url 组合会得到明确失败，不会静默变成搜索。

### 阶段 B：章节规划识别 URL

工作：

- 修改 `chapter_structure_prompt`，要求从原始 query 中原样输出 URL。
- 修改 `query_decompose()` 和 `parse_report_structure()` 支持 `actions`。
- 后端增加 URL 确定性扫描作为兜底。
- 如果模型漏掉用户 URL，记录 `URL_PLAN_FALLBACK`，并把兜底动作放入第一章节。
- 更新 Java `DeepSearchTool` 描述：`query` 可以直接包含 URL。

验证：

- query 中包含 1～3 个 URL 时，规划结果能保留这些 URL。
- URL 不进入搜索词。
- 无 URL 的旧 query 行为不变。

### 阶段 C：精确抓取与缓存

工作：

- 新增 URL 安全校验器。
- 新增运行级 `UrlFetchCache`。
- 新增 `WebFetcher` 到 `Doc` 的适配。
- 在章节初始动作中并发执行 URL 抓取和关键词搜索。
- 把 URL 正文加入章节初始总结。

验证：

- 同一 URL 被多个章节引用时只抓取一次。
- URL 正文确实进入 `S0`。
- URL 抓取失败不影响关键词搜索。
- 引用链接中的 URL 与正文来源一致。

### 阶段 D：链接候选与 Reviewer 下钻

工作：

- `WebFetcher` 提取结构化页面链接。
- `ChapterState` 保存候选链接和抓取深度。
- Reviewer 上下文增加 `link_candidates`。
- Reviewer prompt 支持 `fetch_url`，但只能选择候选链接。
- URL 抓取后的结果继续走现有 Editor 编辑流程。

验证：

- Reviewer 能选择一个候选链接继续抓取。
- 不能从模型凭空生成的 URL 发起请求。
- 达到深度或数量上限后停止。
- 新页面正文通过 Editor 回填当前 summary。

### 阶段 E：观测性与限流

工作：

- 记录 `URL_ACTION_PLANNED`、`URL_ACTION_SELECTED`、`URL_FETCH_FAILED`、`URL_LINK_CANDIDATES`。
- 记录每章抓取数量、链接候选数量、成功/失败数量。
- 增加默认上限：

```text
每章最多 URL 抓取数：8
每页最多链接候选数：20
URL 并发数：4
URL 最大深度：1
每轮总动作数：沿用现有配置
```

验证：

- 日志中可以看到规划、选择、抓取和失败原因。
- 单个坏链接不会拖垮整个请求。
- 运行时间和上下文没有明显失控。

### 阶段 F：真实运行验证与回归

工作：

- 使用本地固定 HTML fixture 做确定性测试。
- 使用真实公开页面做小样本运行。
- 验证报告中的 URL 正文、页面链接和最终引用。
- 验证现有无 URL 主题调研路径不回归。

验证：

- 1 个用户 URL 能进入章节总结。
- 1 个 URL 页面中的候选链接能被 Reviewer 选中并抓取。
- 最终报告包含用户 URL 和新增页面 URL。
- 不出现内部来源编号。
- 现有的 DeepSearch 单元测试和集成测试通过。

## 11. 测试矩阵

### 确定性测试

| 模块 | 测试 |
|---|---|
| `query_process` | 新 `actions`、旧 `search_queries`、URL 保留、URL 去重 |
| `research` | `ResearchAction` 类型校验、URL/query 互斥、枚举归一化 |
| `web_fetcher` | HTML 正文、重定向、空正文、链接提取、内容类型限制 |
| URL 安全 | 回环、私网、危险重定向、非法 scheme |
| URL 缓存 | 同 URL 只请求一次、失败缓存、跨章节复用 |
| `deepsearch` | 初始 URL 抓取、搜索并发、失败降级 |
| `reviewer` | 候选 URL 选择、禁止凭空 URL、动作上限 |
| `summary_edit` | URL 编辑回填、URL 反查来源、原子失败 |
| 报告合并 | URL 引用保留、内部来源编号不进入正文 |

### 小样本运行

至少准备两类题目：

1. 无 URL：纯主题调研，确认旧流程不变。
2. 有 URL：用户给出 1～2 个页面，并包含一个可下钻链接，确认页面正文进入总结、链接被选中抓取、最终引用有效。

## 12. 验收标准

功能验收：

- 用户不需要额外传 `seedUrls`。
- query 中的 URL 能被识别并精确抓取。
- URL 正文能参与章节初始总结。
- 页面中的链接能成为 Reviewer 候选。
- Reviewer 能选择 URL 继续下钻。
- 抓取结果能通过 Editor 回填当前总结。
- 最终报告能保留原始 URL 引用。

稳定性验收：

- URL 失败不会导致整个任务失败。
- 同一 URL 不重复抓取。
- 深度、数量、并发和上下文都有上限。
- 现有无 URL 流程通过回归测试。
- 不出现 `R1-S1` 等内部来源编号。

安全验收：

- 非法 scheme、私网地址和危险重定向被拒绝。
- 网页正文不被当作系统指令执行。
- 不打印密钥、Cookie 或完整敏感页面内容。

## 13. 非目标

以下内容不在本计划首版中：

- 通用网页爬虫。
- 自动遍历整个站点。
- 登录态、验证码和反爬绕过。
- PDF 文件自动解析。
- 必须执行 JavaScript 才能显示正文的页面。
- 为每个 URL 单独生成一个章节。
- 将 URL 抓取失败直接写成最终报告的“信息缺口”小节。

## 14. 预计改动文件

Python：

- `cute-tool/cute_tool/model/research.py`
- `cute-tool/cute_tool/model/protocal.py`（如动作计划需要复用请求模型）
- `cute-tool/cute_tool/tool/search_component/query_process.py`
- `cute-tool/cute_tool/tool/search_component/reviewer.py`
- `cute-tool/cute_tool/tool/search_component/source_ledger.py`
- `cute-tool/cute_tool/tool/search_component/summary_edit.py`
- `cute-tool/cute_tool/tool/web_fetcher.py`
- `cute-tool/cute_tool/tool/deepsearch.py`
- `cute-tool/cute_tool/prompt/deepsearch.yaml`

Java：

- `Cute-agent-domain/src/main/java/org/wwz/ai/domain/agent/runtime/tool/common/DeepSearchTool.java`
- 仅更新工具描述和 query 使用约束，不增加公共 URL 入参。

测试：

- `cute-tool/tests/test_query_process.py`
- `cute-tool/tests/test_web_fetcher.py`
- `cute-tool/tests/test_source_ledger.py`
- `cute-tool/tests/test_research_reviewer.py`
- `cute-tool/tests/test_deepsearch_iterative_flow.py`
- 必要时新增 `test_url_action_security.py`、`test_url_fetch_cache.py`

## 15. 最终定义

完成本计划后，DeepSearch 对用户的表现应当是：

> 用户把主题和 URL 写在同一个自然语言问题里；DeepSearch 内部识别 URL，先精确读取指定页面，再根据页面内容和未解决缺口继续搜索或沿页面链接下钻，最后输出一份引用原始网页链接的可追溯报告。

工程上应当是：

> URL 不是特殊入参，而是与搜索关键词同级的 ResearchAction；模型负责规划和选择，确定性代码负责校验、抓取、去重、预算控制和来源追踪。

## 16. 实施结果（2026-11-14）

已完成：

- `ResearchAction` 统一承载 `web_search` 和 `web_fetch`，旧 `search_queries` 继续兼容。
- 章节规划支持从用户 query 识别 URL；模型漏识别时使用 `URL_PLAN_FALLBACK` 补到第一章。
- DeepSearch 内部已接入 `WebFetcher`、运行级 `UrlFetchCache`、公开 URL 安全校验和逐跳重定向校验。
- 精确 URL 抓取结果优先于同一 URL 的搜索摘要，避免搜索结果覆盖网页正文和链接候选。
- `WebFetcher` 支持提取页面链接候选；Reviewer 只能从候选白名单选择 `fetch_url`。
- 已实现每章 8 个 URL、单页 20 个候选、URL 并发 4、最大深度 1 的默认边界及对应日志。
- 新增来源的 `origin`、`depth`、`parent_url`、`original_url` 元数据；内部 `source_id` 仍不进入模型上下文。
- Java `DeepSearchTool` 描述已说明 `query` 可以直接包含 URL，未增加公共 URL 入参。

验证：

- DeepSearch/Reviewer/WebFetcher/URL 安全与缓存定向测试：`89 passed`。
- 完整 `cute-tool/tests`：`573 passed / 2 skipped / 11 failed`；11 个失败均为既有 Grok 鉴权、
  HuggingFace 模型下载、E2B 沙箱和 Windows 路径问题，与本次改动无关。
- Java：`mvn.cmd -pl Cute-agent-domain -am -DskipTests compile` 通过。
- 真实 URL 冒烟使用 `https://www.python.org/about/`：
  - 用户 URL 被精确抓取并进入章节总结；
  - 精确抓取文档保留了 20 个页面链接候选；
  - 最终报告约 15,162 字、72 个可点击 Markdown URL；
  - 裸内部来源编号为 0。
- Reviewer 选择候选链接并触发第二轮 `fetch_url` 的路径由确定性集成测试覆盖；实时样本中
  Reviewer 判断首轮材料已经充分，因此没有发生第二轮下钻。
