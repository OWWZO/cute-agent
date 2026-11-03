package org.wwz.ai.domain.agent.catalog.port;

import org.wwz.ai.domain.agent.catalog.model.CatalogModel;

import java.util.List;

/**
 * 模型目录运行时端口，承接目录读取和管理写入后的缓存失效。
 */
public interface ICatalogModelRuntimePort {

    /**
     * 返回当前用户可选择的模型目录快照。
     * <p>返回值不可变、永不为 null；无可用模型时返回空列表。读取本身线程安全，
     * 调用方不得修改返回列表（共享缓存视图）——实现应返回防御性副本或不可变视图。</p>
     *
     * @return 不可变模型列表；无可用模型时为空列表
     */
    List<CatalogModel> listUserSelectableModels();

    /**
     * 使目录缓存失效，下一次读取重新加载。
     * <p>可在任意线程调用。失效与并发读取之间不保证强一致：正在进行的读取可能仍返回旧快照，
     * 失效之后开始的读取才保证看到新数据。</p>
     */
    void invalidateAll();
}
