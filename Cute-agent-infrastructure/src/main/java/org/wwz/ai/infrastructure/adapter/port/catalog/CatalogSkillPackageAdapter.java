package org.wwz.ai.infrastructure.adapter.port.catalog;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.wwz.ai.domain.agent.catalog.model.CatalogSkillPackageResult;
import org.wwz.ai.domain.agent.catalog.port.ICatalogSkillPackagePort;
import org.wwz.ai.domain.agent.runtime.tool.skill.SkillPackageService;

/** 技能包技术执行器适配器。 */
@Component
@RequiredArgsConstructor
public class CatalogSkillPackageAdapter implements ICatalogSkillPackagePort {
    private final SkillPackageService skillPackageService;

    @Override
    public CatalogSkillPackageResult previewZip(byte[] bytes) {
        return new CatalogSkillPackageResult(skillPackageService.previewZipAsMap(bytes));
    }

    @Override
    public CatalogSkillPackageResult installZip(byte[] bytes, String filename, boolean replace) {
        return new CatalogSkillPackageResult(skillPackageService.installZip(bytes, filename, replace));
    }

    @Override
    public CatalogSkillPackageResult installFromMarkdown(String name, String description, String content, boolean replace) {
        return new CatalogSkillPackageResult(skillPackageService.installFromMarkdown(name, description, content, replace));
    }

    @Override
    public CatalogSkillPackageResult installFromUrl(String url, boolean replace) {
        return new CatalogSkillPackageResult(skillPackageService.installFromUrl(url, replace));
    }
}
