package org.wwz.ai.domain.agent.runtime.tool.skill;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 附属文件不存在时带上可用清单，便于模型改用正确路径。
 */
public class SkillFileNotFoundException extends SkillLoadException {

    private final Map<String, List<String>> availableFiles;

    public SkillFileNotFoundException(String relativePath, Map<String, List<String>> availableFiles) {
        super("File '" + relativePath + "' not found in skill.");
        this.availableFiles = copyPreservingOrder(availableFiles);
    }

    public Map<String, List<String>> getAvailableFiles() {
        return availableFiles;
    }

    /**
     * 保留描述符顺序并深拷贝内层列表：available_files 提示必须稳定可复现，
     * 且不能继续引用调用方的可变集合。
     */
    private static Map<String, List<String>> copyPreservingOrder(Map<String, List<String>> availableFiles) {
        if (availableFiles == null) {
            return Map.of();
        }
        Map<String, List<String>> copy = new LinkedHashMap<>();
        for (Map.Entry<String, List<String>> entry : availableFiles.entrySet()) {
            copy.put(entry.getKey(), List.copyOf(entry.getValue()));
        }
        return Collections.unmodifiableMap(copy);
    }
}
