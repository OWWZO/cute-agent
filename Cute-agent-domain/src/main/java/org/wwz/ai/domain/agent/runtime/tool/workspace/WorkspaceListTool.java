package org.wwz.ai.domain.agent.runtime.tool.workspace;

import org.apache.commons.lang3.StringUtils;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;

/**
 * 列出工作区目录。
 */
public class WorkspaceListTool extends AbstractWorkspacePathTool {

    public WorkspaceListTool(WorkspaceService workspaceService, WorkspaceRuntimeOptions workspaceRuntimeOptions) {
        super(workspaceService, workspaceRuntimeOptions);
    }

    @Override
    public String getName() {
        return "workspace_list";
    }

    @Override
    public String getDescription() {
        return withWorkspaceHint("列出会话工作区内的文件和子目录。path 默认可为工作区根或其下目录；可用 max_depth 控制遍历深度。");
    }

    @Override
    public Map<String, Object> toParams() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("path", Map.of("type", "string", "description", "目录路径（绝对或相对工作区根）；缺省为工作区根"));
        properties.put("max_depth", Map.of("type", "integer", "description", "最大遍历深度，默认 2"));

        Map<String, Object> parameters = new LinkedHashMap<>();
        parameters.put("type", "object");
        parameters.put("properties", properties);
        parameters.put("required", List.of());
        return parameters;
    }

    @Override
    public Object execute(Object input, AgentContext context) {
        try {
            Map<String, Object> params = requireInputMap(input == null ? Map.of() : input);
            Path directoryPath;
            Object pathValue = params.get("path");
            if (pathValue == null || String.valueOf(pathValue).isBlank()) {
                directoryPath = requireWorkspaceRoot(context);
            } else {
                directoryPath = requireAllowedPath(params, context);
            }
            if (!Files.isDirectory(directoryPath)) {
                return failResult("workspace_list 只支持目录路径: " + toAgentPath(directoryPath, context));
            }

            int maxDepth = Math.max(1, readInt(params, "max_depth", 2));
            List<Map<String, Object>> entriesOut = new ArrayList<>();
            boolean[] truncated = {false};
            int maxEntries = workspaceRuntimeOptions.getMaxListEntries();
            // walkFileTree 的 visitor 已带 BasicFileAttributes（类型/大小），
            // 不再对每个条目额外调用 Files.isDirectory / isRegularFile / size 三次 stat。
            Files.walkFileTree(directoryPath, Set.of(), maxDepth, new SimpleFileVisitor<>() {
                @Override
                public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) {
                    return addEntry(dir, "DIR", null);
                }

                @Override
                public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (attrs.isDirectory()) {
                        return addEntry(file, "DIR", null);
                    }
                    if (attrs.isSymbolicLink()) {
                        // 未跟随符号链接时沿用旧语义：类型与大小按目标判定。
                        Long bytes = Files.isRegularFile(file) ? Files.size(file) : null;
                        return addEntry(file, Files.isDirectory(file) ? "DIR" : "FILE", bytes);
                    }
                    return addEntry(file, "FILE", attrs.isRegularFile() ? attrs.size() : null);
                }

                private FileVisitResult addEntry(Path entry, String type, Long bytes) {
                    if (entry.equals(directoryPath)) {
                        return FileVisitResult.CONTINUE;
                    }
                    if (entriesOut.size() >= maxEntries) {
                        truncated[0] = true;
                        return FileVisitResult.TERMINATE;
                    }
                    Map<String, Object> row = new LinkedHashMap<>();
                    row.put("type", type);
                    row.put("path", toRelativePath(directoryPath, entry));
                    if (bytes != null) {
                        row.put("bytes", bytes);
                    }
                    entriesOut.add(row);
                    return FileVisitResult.CONTINUE;
                }
            });
            Map<String, Object> data = new LinkedHashMap<>();
            data.put("path", toAgentPath(directoryPath, context));
            data.put("entries", entriesOut);
            if (truncated[0]) {
                data.put("truncated", Boolean.TRUE);
            }
            return okResult(data);
        } catch (WorkspaceAccessException e) {
            log.warn("{} workspace_list failed, input={}", requestId(context), input, e);
            return failResult(e.getMessage());
        } catch (IOException e) {
            log.error("{} workspace_list io error, input={}", requestId(context), input, e);
            return failResult("workspace_list execute failed: " + describe(e));
        } catch (Exception e) {
            log.error("{} workspace_list error, input={}", requestId(context), input, e);
            return failResult("workspace_list execute failed: " + describe(e));
        }
    }

    private static String describe(Exception e) {
        return StringUtils.defaultIfBlank(e.getMessage(), e.getClass().getSimpleName());
    }
}
