package org.wwz.ai.domain.agent.ledger.replay;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.wwz.ai.domain.agent.ledger.model.ArtifactView;
import org.wwz.ai.domain.agent.runtime.artifact.WorkspaceRelativePath;

import java.util.Map;

/**
 * 从 artifact 元数据还原工作区相对路径，供历史回放的 fileInfo / artifactRefs 建树。
 */
@Slf4j
public final class ArtifactRelativePath {

    private ArtifactRelativePath() {
    }

    public static String resolve(ArtifactView artifact) {
        if (artifact == null) {
            return "";
        }
        // 元数据来自 artifact JSON，不可信：归一化后仍无法证明落在工作区内就直接丢弃，
        // 回退到文件名；不再把 ../../ 或盘符路径原样带进回放载荷。
        String fromMeta = WorkspaceRelativePath.sanitize(readMetadataPath(artifact.getMetadataJson()));
        if (StringUtils.isNotBlank(fromMeta)) {
            return fromMeta;
        }
        return WorkspaceRelativePath.sanitize(artifact.getFileName());
    }

    public static void putOn(Map<String, Object> target, ArtifactView artifact) {
        if (target == null) {
            return;
        }
        String relativePath = resolve(artifact);
        if (StringUtils.isBlank(relativePath)) {
            return;
        }
        // 元数据路径比工具输出合并阶段的退化值更可信：只有已存在的路径至少同样具体
        // （含目录分隔符）时才保留，否则用元数据路径覆盖，避免嵌套产物被渲染到错误层级。
        if (!isSpecificPath(target.get("relativePath"))) {
            target.put("relativePath", relativePath);
        }
        if (!isSpecificPath(target.get("originFileName"))) {
            target.put("originFileName", relativePath);
        }
    }

    private static boolean isSpecificPath(Object value) {
        if (!(value instanceof String text)) {
            return false;
        }
        return text.indexOf('/') >= 0 || text.indexOf('\\') >= 0;
    }

    private static String readMetadataPath(String metadataJson) {
        if (StringUtils.isBlank(metadataJson)) {
            return null;
        }
        try {
            JSONObject object = JSON.parseObject(metadataJson);
            if (object == null) {
                return null;
            }
            String relativePath = object.getString("relativePath");
            if (StringUtils.isNotBlank(relativePath)) {
                return relativePath;
            }
            String originFileName = object.getString("originFileName");
            if (StringUtils.isNotBlank(originFileName)
                    && (originFileName.contains("/") || originFileName.contains("\\"))) {
                return originFileName;
            }
            String description = object.getString("description");
            if (StringUtils.startsWith(description, "workspace:")) {
                return description.substring("workspace:".length());
            }
        } catch (Exception ignore) {
            // 元数据损坏时回退到文件名，但仍留下诊断线索，避免整棵回放树无提示降级。
            log.debug("artifact 元数据解析失败，回退文件名: metadata={}",
                    StringUtils.abbreviate(metadataJson, 500), ignore);
            return null;
        }
        return null;
    }
}
