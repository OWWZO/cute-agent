package org.wwz.ai.domain.agent.runtime.tool.common.mcp;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.tool.ToolArgs;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.mcp.port.McpToolExecutor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * 读取 MCP resource。
 */
@Slf4j
public class ReadMcpResourceTool implements BaseTool {

    /** 单次读取返回给模型的最大字符数，超出部分截断并标记。 */
    private static final int MAX_CONTENT_CHARS = 40_000;

    @Override
    public String getName() {
        return McpToolNames.READ_MCP_RESOURCE;
    }

    @Override
    public String getDescription() {
        return "读取指定 MCP 服务器上的 resource 内容。"
                + " 需要 server（mcpId 或 serverKey）与 uri。"
                + " 文本直接返回；二进制 blob 以摘要形式返回（不内嵌完整 base64）。";
    }

    @Override
    public Map<String, Object> toParams() {
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("type", "string");
        server.put("description", "mcpId 或 serverKey");
        properties.put("server", server);

        Map<String, Object> uri = new LinkedHashMap<>();
        uri.put("type", "string");
        uri.put("description", "resource URI");
        properties.put("uri", uri);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", java.util.List.of("server", "uri"));
        return parameters;
    }

    @Override
    public Object execute(Object input, AgentContext context) {
        try {
            McpToolExecutor executor = resolveExecutor(context);
            if (executor == null) {
                return ToolResultPayload.failure("ReadMcpResource 失败：McpToolExecutor 不可用",
                        "ReadMcpResource 失败：McpToolExecutor 不可用", null, "no executor");
            }
            Map<String, Object> args = ToolArgs.map(input);
            String server = stringArg(args, "server");
            String uri = stringArg(args, "uri");
            if (StringUtils.isBlank(server) || StringUtils.isBlank(uri)) {
                return ToolResultPayload.failure("ReadMcpResource 失败：server 与 uri 必填",
                        "ReadMcpResource 失败：server 与 uri 必填", null, "invalid args");
            }
            String content = executor.readResource(server, uri);
            boolean truncated = content != null && content.length() > MAX_CONTENT_CHARS;
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("server", server);
            fields.put("uri", uri);
            fields.put("content", truncated ? content.substring(0, MAX_CONTENT_CHARS) : content);
            if (truncated) {
                fields.put("truncated", Boolean.TRUE);
                fields.put("originalChars", content.length());
            }
            return ToolResultPayload.okData(McpToolNames.READ_MCP_RESOURCE, fields);
        } catch (Exception e) {
            log.warn("ReadMcpResource failed", e);
            // 异常细节只留在服务端日志，避免把主机名/路径/连接串带进模型上下文。
            return ToolResultPayload.failureFrom("ReadMcpResource 失败，详见服务端日志。", null);
        }
    }

    private McpToolExecutor resolveExecutor(AgentContext context) {
        if (context == null || context.getRuntimeDependencies() == null) {
            return null;
        }
        return context.getRuntimeDependencies().getOptionalMcpToolExecutor();
    }

    private static String stringArg(Map<String, Object> args, String key) {
        Object value = args.get(key);
        return value == null ? null : String.valueOf(value).trim();
    }

}
