package org.wwz.ai.application.catalog.capability;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.wwz.ai.application.catalog.ApplicationResult;
import org.wwz.ai.application.catalog.mcp.McpCatalogApplicationService;
import org.wwz.ai.application.catalog.skill.SkillCatalogApplicationService;
import org.wwz.ai.domain.agent.catalog.model.CatalogCapabilities;
import org.wwz.ai.domain.agent.catalog.model.CatalogMcp;
import org.wwz.ai.domain.agent.catalog.model.CatalogSkill;
import org.wwz.ai.types.enums.ResponseCode;

import java.util.List;

/**
 * 对外能力目录的组合查询用例。
 */
@Service
@Slf4j
@RequiredArgsConstructor
public class CatalogCapabilitiesApplicationService {

    private final SkillCatalogApplicationService skillCatalogApplicationService;
    private final McpCatalogApplicationService mcpCatalogApplicationService;

    public ApplicationResult<CatalogCapabilities> listCapabilities() {
        ApplicationResult<List<CatalogSkill>> skills = skillCatalogApplicationService.listSkills();
        if (skills == null) {
            log.error("技能目录返回 null");
            return ApplicationResult.failure(ResponseCode.UN_ERROR, "技能目录不可用", null);
        }
        if (!isSuccess(skills)) {
            return downstreamFailure(skills);
        }
        ApplicationResult<List<CatalogMcp>> mcps = mcpCatalogApplicationService.listMcps();
        if (mcps == null) {
            log.error("MCP 目录返回 null");
            return ApplicationResult.failure(ResponseCode.UN_ERROR, "MCP 目录不可用", null);
        }
        if (!isSuccess(mcps)) {
            return downstreamFailure(mcps);
        }
        return ApplicationResult.success(CatalogCapabilities.builder()
                .skills(skills.data() == null ? List.of() : skills.data())
                .mcps(mcps.data() == null ? List.of() : mcps.data())
                .build());
    }

    private static boolean isSuccess(ApplicationResult<?> result) {
        return result != null && ResponseCode.SUCCESS.getCode().equals(result.code());
    }

    private static <T> ApplicationResult<T> downstreamFailure(ApplicationResult<?> result) {
        if (result == null || result.code() == null || result.code().isBlank()) {
            return ApplicationResult.failure(ResponseCode.UN_ERROR, result == null ? null : result.info(), null);
        }
        return new ApplicationResult<>(result.code(), result.info(), null);
    }
}
