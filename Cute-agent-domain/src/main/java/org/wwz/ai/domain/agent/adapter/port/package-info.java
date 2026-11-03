/**
 * Agent 外部能力端口（浏览器/relay、文件产物、远端 HTTP/流、子 Agent、CLI）。
 * domain 在这里声明这些能力契约，具体实现必须下沉到 infrastructure。
 * LLM 出站能力不在本包：见 {@code org.wwz.ai.domain.agent.runtime.llm.LlmCompletionPort}
 * 与 {@code org.wwz.ai.domain.agent.catalog.port} 下的模型目录/连通性端口。
 */
package org.wwz.ai.domain.agent.adapter.port;
