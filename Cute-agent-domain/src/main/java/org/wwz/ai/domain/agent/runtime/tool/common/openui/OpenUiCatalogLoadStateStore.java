package org.wwz.ai.domain.agent.runtime.tool.common.openui;

import com.alibaba.fastjson.JSON;
import com.alibaba.fastjson.JSONObject;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import org.wwz.ai.domain.agent.runtime.agent.AgentContext;
import org.wwz.ai.domain.agent.runtime.tool.workspace.WorkspaceReadStateStore;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * OpenUI 组件目录的跨轮已加载标记。
 *
 * <p>只保存目录内容 hash 与时间戳，不保存目录正文；子 Agent 不读写主会话 sidecar。</p>
 */
@Slf4j
@Component
public class OpenUiCatalogLoadStateStore {

    static final String RELATIVE_STORE_PATH = ".cute/openui-catalog-loaded.json";

    public void hydrate(AgentContext agentContext) {
        if (agentContext == null || StringUtils.isNotBlank(agentContext.getLoadedOpenUiCatalogVersion())
                || StringUtils.isBlank(agentContext.getWorkspaceRoot()) || isSubAgent(agentContext)) {
            return;
        }
        Path storeFile = storeFile(agentContext.getWorkspaceRoot());
        if (!Files.isRegularFile(storeFile)) {
            return;
        }
        try {
            String json = Files.readString(storeFile, StandardCharsets.UTF_8);
            if (StringUtils.isBlank(json)) {
                return;
            }
            JSONObject root = JSON.parseObject(json);
            if (root == null) {
                return;
            }
            String version = root.getString("version");
            if (StringUtils.isNotBlank(version)) {
                agentContext.setLoadedOpenUiCatalogVersion(version);
            }
        } catch (Exception e) {
            log.warn("{} hydrate OpenUI catalog load-state failed, path={}",
                    agentContext.getRequestId(), storeFile, e);
        }
    }

    public void persist(AgentContext agentContext, String version) {
        if (agentContext == null || StringUtils.isBlank(version)
                || StringUtils.isBlank(agentContext.getWorkspaceRoot()) || isSubAgent(agentContext)) {
            return;
        }
        Path storeFile = storeFile(agentContext.getWorkspaceRoot());
        try {
            Path parent = storeFile.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            JSONObject root = new JSONObject(true);
            root.put("version", version);
            root.put("loadedAt", System.currentTimeMillis());
            Files.writeString(storeFile, root.toJSONString(), StandardCharsets.UTF_8);
        } catch (Exception e) {
            log.warn("{} persist OpenUI catalog load-state failed, path={}",
                    agentContext.getRequestId(), storeFile, e);
        }
    }

    /**
     * 清当前 context 的内存标记；主 Agent 同时删除会话 workspace sidecar。
     * 子 Agent 只清内存，不能删除父会话的跨轮状态。
     */
    public void clear(AgentContext agentContext) {
        if (agentContext == null) {
            return;
        }
        agentContext.setLoadedOpenUiCatalogVersion(null);
        if (!isSubAgent(agentContext)) {
            clearPersisted(agentContext.getWorkspaceRoot());
        }
    }

    /** 压缩发生在 pre-run、尚无 AgentContext 时，按 workspaceRoot 删除 sidecar。 */
    public void clearPersisted(String workspaceRoot) {
        if (StringUtils.isBlank(workspaceRoot)) {
            return;
        }
        Path storeFile = storeFile(workspaceRoot);
        try {
            if (Files.deleteIfExists(storeFile)) {
                log.info("cleared OpenUI catalog load-state path={}", storeFile);
            }
        } catch (Exception e) {
            log.warn("clear OpenUI catalog load-state failed, path={}", storeFile, e);
        }
    }

    public static String versionOf(String catalog) {
        return WorkspaceReadStateStore.sha256Hex(catalog);
    }

    private Path storeFile(String workspaceRoot) {
        return Path.of(workspaceRoot).toAbsolutePath().normalize().resolve(RELATIVE_STORE_PATH);
    }

    private boolean isSubAgent(AgentContext agentContext) {
        return StringUtils.isNotBlank(agentContext.getSubAgentId());
    }
}
