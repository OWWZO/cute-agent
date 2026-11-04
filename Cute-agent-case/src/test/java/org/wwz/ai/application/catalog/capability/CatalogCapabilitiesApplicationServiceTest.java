package org.wwz.ai.application.catalog.capability;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.application.catalog.ApplicationResult;
import org.wwz.ai.application.catalog.mcp.McpCatalogApplicationService;
import org.wwz.ai.application.catalog.skill.SkillCatalogApplicationService;
import org.wwz.ai.domain.agent.catalog.model.CatalogMcp;
import org.wwz.ai.domain.agent.catalog.model.CatalogSkill;
import org.wwz.ai.types.enums.ResponseCode;

import java.util.List;

public class CatalogCapabilitiesApplicationServiceTest {

    @Test
    public void propagatesSkillFailureCodeAndInfo() {
        CatalogCapabilitiesApplicationService service = service(
                ApplicationResult.failure(ResponseCode.ILLEGAL_PARAMETER, "skill unavailable", null),
                ApplicationResult.success(List.of()));

        ApplicationResult<?> result = service.listCapabilities();

        Assert.assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), result.code());
        Assert.assertEquals("skill unavailable", result.info());
    }

    @Test
    public void nullMcpResultBecomesControlledFailure() {
        CatalogCapabilitiesApplicationService service = service(
                ApplicationResult.success(List.<CatalogSkill>of()),
                null);

        ApplicationResult<?> result = service.listCapabilities();

        Assert.assertEquals(ResponseCode.UN_ERROR.getCode(), result.code());
        Assert.assertEquals("MCP 目录不可用", result.info());
    }

    private static CatalogCapabilitiesApplicationService service(
            ApplicationResult<List<CatalogSkill>> skills,
            ApplicationResult<List<CatalogMcp>> mcps) {
        SkillCatalogApplicationService skillService = new SkillCatalogApplicationService(null, null) {
            @Override
            public ApplicationResult<List<CatalogSkill>> listSkills() {
                return skills;
            }
        };
        McpCatalogApplicationService mcpService = new McpCatalogApplicationService(null, null, null) {
            @Override
            public ApplicationResult<List<CatalogMcp>> listMcps() {
                return mcps;
            }
        };
        return new CatalogCapabilitiesApplicationService(skillService, mcpService);
    }
}
