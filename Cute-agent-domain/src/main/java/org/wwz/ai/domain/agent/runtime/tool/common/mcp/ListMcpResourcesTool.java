package org.wwz.ai.domain.agent.runtime.tool.common.mcp;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.tool.mcp.model.McpResourceInfo;
import org.wwz.ai.domain.agent.runtime.tool.BaseTool;
import org.wwz.ai.domain.agent.runtime.tool.ToolArgs;
import org.wwz.ai.domain.agent.runtime.tool.ToolResultPayload;
import org.wwz.ai.domain.agent.runtime.tool.mcp.port.McpToolExecutor;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 列出 MCP resources。
 */
@Slf4j
public class ListMcpResourcesTool implements BaseTool {

    /** 单个 MCP server 可能暴露上百条 resource，全量回传会挤爆上下文窗口。 */
    private static final int MAX_RETURNED_RESOURCES = 100;

    @Override
    public String getName() {
        return McpToolNames.LIST_MCP_RESOURCES;
    }

    @Override
    public String getDescription() {
        return "列出已连接 MCP 服务器上的 resources。"
                + " 可选 server 过滤（mcpId 或 serverKey）。"
                + " 读取内容请用 ReadMcpResource。";
    }

    @Override
    public Map<String, Object> toParams() {
        Map<String, Object> properties = new LinkedHashMap<>();
        Map<String, Object> server = new LinkedHashMap<>();
        server.put("type", "string");
        server.put("description", "可选：mcpId 或 serverKey，过滤单个 MCP 服务器");
        properties.put("server", server);

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", Collections.emptyList());
        return parameters;
    }

    @Override
    public Object execute(Object input, AgentContext context) {
        try {
            McpToolExecutor executor = resolveExecutor(context);
            if (executor == null) {
                return ToolResultPayload.failure("ListMcpResources 失败：McpToolExecutor 不可用",
                        "ListMcpResources 失败：McpToolExecutor 不可用", null, "no executor");
            }
            Map<String, Object> args = ToolArgs.map(input);
            String server = stringArg(args, "server");
            List<McpResourceInfo> resources = StringUtils.isBlank(server)
                    ? executor.listGlobalResources()
                    : executor.listResources(server);

            int returned = Math.min(resources.size(), MAX_RETURNED_RESOURCES);
            List<Map<String, Object>> rows = new ArrayList<>(returned);
            for (int i = 0; i < returned; i++) {
                McpResourceInfo resource = resources.get(i);
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("uri", resource.getUri());
                row.put("name", resource.getName());
                row.put("title", resource.getTitle());
                row.put("description", resource.getDescription());
                row.put("mimeType", resource.getMimeType());
                row.put("size", resource.getSize());
                row.put("server", resource.getServerKey());
                row.put("mcpId", resource.getMcpId());
                rows.add(row);
            }
            Map<String, Object> fields = new LinkedHashMap<>();
            fields.put("resources", rows);
            fields.put("count", rows.size());
            if (resources.size() > returned) {
                fields.put("total", resources.size());
                fields.put("truncated", Boolean.TRUE);
            }
            return ToolResultPayload.okData(McpToolNames.LIST_MCP_RESOURCES, fields);
        } catch (Exception e) {
            log.warn("ListMcpResources failed", e);
            // MCP 连接异常的 message 常含内部地址 / 端口 / token，对外只给通用文案，细节留在日志。
            return ToolResultPayload.failureFrom("ListMcpResources 失败：MCP 资源列表读取异常", null);
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
