package org.wwz.ai.test.application.catalog.skill;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.Mockito;
import org.wwz.ai.application.catalog.ApplicationResult;
import org.wwz.ai.application.catalog.skill.SkillCatalogApplicationService;
import org.wwz.ai.application.catalog.skill.SkillCreateCommand;
import org.wwz.ai.domain.agent.catalog.port.ICatalogSkillPackagePort;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillLoadException;
import org.wwz.ai.types.enums.ResponseCode;

import java.util.Map;

public class SkillCatalogFailureMessageTest {

    @Test
    public void skillLoadExceptionMessageIsPreserved() {
        ApplicationResult<Map<String, Object>> result =
                service(new SkillLoadException("缺少 SKILL.md"))
                        .createSkill(new SkillCreateCommand("demo", "demo skill", "---\nname: demo\n---\nbody"));

        Assert.assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), result.code());
        Assert.assertTrue(result.info().contains("缺少 SKILL.md"));
    }

    @Test
    public void unexpectedExceptionKeepsGenericFallback() {
        ApplicationResult<Map<String, Object>> result =
                service(new NullPointerException("internal stack detail"))
                        .createSkill(new SkillCreateCommand("demo", "demo skill", "body"));

        Assert.assertEquals(ResponseCode.ILLEGAL_PARAMETER.getCode(), result.code());
        Assert.assertEquals("技能创建失败", result.info());
        Assert.assertFalse(result.info().contains("internal stack detail"));
    }

    private static SkillCatalogApplicationService service(RuntimeException failure) {
        ICatalogSkillPackagePort packagePort = Mockito.mock(ICatalogSkillPackagePort.class);
        Mockito.when(packagePort.installFromMarkdown(
                        Mockito.anyString(), Mockito.anyString(), Mockito.anyString(), Mockito.eq(false)))
                .thenThrow(failure);
        return new SkillCatalogApplicationService(null, packagePort);
    }
}
