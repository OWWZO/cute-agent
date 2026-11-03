package org.wwz.ai.domain.agent.catalog.port;

import org.wwz.ai.domain.agent.catalog.model.ModelConnectionTestResult;

/**
 * 模型连接探测端口。
 * <p>方法始终返回非 {@code null} 结果：连接失败通过 {@link ModelConnectionTestResult#ok()} 为 {@code false}
 * 表达，不抛出异常。{@code modelReference}/{@code id} 须非空。</p>
 */
public interface IModelConnectionTestPort {

    ModelConnectionTestResult test(String modelReference);

    ModelConnectionTestResult testByRecordId(Long id);
}
