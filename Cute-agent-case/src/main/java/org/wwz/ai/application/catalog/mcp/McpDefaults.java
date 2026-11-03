package org.wwz.ai.application.catalog.mcp;

/**
 * MCP 创建默认值。
 * <p>
 * 管理端 CRUD 与普通目录创建是同一个能力的两条入口，默认值必须同源，
 * 否则同一份配置在两条路径下会写入不同的 transportType / requestTimeout / status。
 */
final class McpDefaults {

    static final String DEFAULT_TRANSPORT_TYPE = "streamable_http";

    /**
     * 默认请求超时数值。单位由 transport 决定，当前实现中 STDIO 按秒解释，
     * SSE/Streamable HTTP 按分钟解释；因此 5 分别表示 5 秒和 5 分钟。
     * <p>调整该常量时必须同步检查 {@code McpClientFactory} 中各 transport 的时间单位。</p>
     */
    static final int DEFAULT_REQUEST_TIMEOUT = 5;

    private McpDefaults() {
    }

    /**
     * 归一化启用状态：只有显式 0 表示禁用；null 以及其他任何非 0 值（包括非法值）
     * 都会被归一为启用 1。需要严格校验输入范围的调用方应在写入前自行拒绝非法值。
     */
    static int normalizeStatus(Integer status) {
        return status != null && status == 0 ? 0 : 1;
    }
}
