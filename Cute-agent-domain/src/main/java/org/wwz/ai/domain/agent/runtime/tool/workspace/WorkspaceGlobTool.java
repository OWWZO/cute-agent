package org.wwz.ai.domain.agent.runtime.tool.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;

/**
 * 工作区 glob 匹配。
 */
public class WorkspaceGlobTool extends AbstractWorkspacePathTool {

    public WorkspaceGlobTool(WorkspaceService workspaceService, WorkspaceRuntimeOptions workspaceRuntimeOptions) {
        super(workspaceService, workspaceRuntimeOptions);
    }

    @Override
    public String getName() {
        return "workspace_glob";
    }

    @Override
    public String getDescription() {
        return withWorkspaceHint("在会话工作区内按 glob 模式查找文件，例如 **/*.java 或 src/**/*.ts。path 为搜索起点目录，可省略则从工作区根开始。"
                + "示例：workspace_glob(path=\"src\", pattern=\"*.py\")；读单个文件请用 workspace_read(path=\"src/a.py\")。"
                + "不要用 find/ls 代替本工具。");
    }

    @Override
    public Map<String, Object> toParams() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", Map.of("type", "string", "description", "搜索起点目录；缺省为工作区根"));
        properties.put("pattern", Map.of("type", "string", "description", "glob 模式，例如 **/*.md"));

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of("pattern"));
        return parameters;
    }

    @Override
    public Object execute(Object input, AgentContext context) {
        try {
            Map<String, Object> params = requireInputMap(input);
            Path basePath;
            Object pathValue = params.get("path");
            if (pathValue == null || String.valueOf(pathValue).isBlank()) {
                basePath = requireWorkspaceRoot(context);
            } else {
                basePath = requireAllowedPath(params, context);
            }
            if (!Files.isDirectory(basePath)) {
                String message = "workspace_glob 只支持目录路径: " + toAgentPath(basePath, context);
                return argumentError(
                        "WORKSPACE_GLOB_PATH_NOT_DIR",
                        message,
                        "Use workspace_read(path=...) for files; workspace_glob path must be a directory."
                );
            }
            Object patternValue = params.get("pattern");
            if (patternValue == null || String.valueOf(patternValue).isBlank()) {
                return argumentError(
                        "WORKSPACE_GLOB_PATTERN_REQUIRED",
                        "pattern is required",
                        "Pass a glob pattern, for example pattern=\"*.py\"."
                );
            }

            String pattern = String.valueOf(patternValue).trim();
            Pattern matcher = buildGlobPattern(pattern);
            List<String> files = new ArrayList<>();
            boolean truncated = false;
            try (var pathStream = Files.walk(basePath)) {
                int resultLimit = Math.max(0, workspaceRuntimeOptions.getMaxGlobResults());
                int scanBudget = Math.max(1, resultLimit);
                var iterator = pathStream.iterator();
                int scanned = 0;
                while (iterator.hasNext()) {
                    Path path = iterator.next();
                    if (path.equals(basePath)) {
                        continue;
                    }
                    if (scanned++ >= scanBudget) {
                        truncated = true;
                        break;
                    }
                    if (Files.isRegularFile(path)
                            && matcher.matcher(toRelativePath(basePath, path)).matches()) {
                        if (files.size() < resultLimit) {
                            files.add(toRelativePath(basePath, path));
                        } else {
                            truncated = true;
                            break;
                        }
                    }
                }
                if (!truncated && scanned >= scanBudget && iterator.hasNext()) {
                    truncated = true;
                }
            }
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("path", toAgentPath(basePath, context));
            data.put("pattern", pattern);
            data.put("files", files);
            if (truncated) {
                data.put("truncated", Boolean.TRUE);
            }
            return okResult(data);
        } catch (WorkspaceAccessException e) {
            log.warn("{} workspace_glob failed, input={}", requestId(context), input, e);
            return failResult(e.getMessage());
        } catch (IOException e) {
            log.error("{} workspace_glob io error, input={}", requestId(context), input, e);
            return failResult("workspace_glob execute failed");
        } catch (Exception e) {
            log.error("{} workspace_glob error, input={}", requestId(context), input, e);
            return failResult("workspace_glob execute failed");
        }
    }
}
