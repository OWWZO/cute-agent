package org.wwz.ai.application.catalog.api;

/**
 * API 配置管理查询命令。
 */
public record ApiConfigQueryCommand(
        String apiId,
        String baseUrl,

        /** 状态过滤条件：1 表示启用，0 表示停用；null 表示不按状态过滤。 */
        Integer status,

        /** 页码，从 1 开始；null 时当前实现默认 1。 */
        Integer pageNum,

        /** 每页条数；null 时当前实现默认 10。 */
        Integer pageSize) {
}
