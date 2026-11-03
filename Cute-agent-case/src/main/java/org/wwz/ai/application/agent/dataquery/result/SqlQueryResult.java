package org.wwz.ai.application.agent.dataquery.result;

import java.util.List;
import java.util.Map;

/**
 * SQL 执行结果的用例视图。
 * <p>该 record 只保证引用不可重新赋值，不保证集合内部不可变。列清单、行数据和行内 Map
 * 均可能与上游执行结果共享，消费方应视为只读，不应在缓存或跨请求复用场景中修改。</p>
 * <p>成功标志、错误信息和各诊断时间字段均可能缺失；在契约未进一步收紧前，消费方必须
 * 按可空字段处理，不能直接对包装类型拆箱。</p>
 */
public record SqlQueryResult(
        /** 最终执行的 SQL 文本；失败路径可能为 null。 */
        String querySql,

        /** 返回行数；正常 JDBC 成功路径等于 {@code dataList.size()}，其他路径可能为 null。 */
        Long dataSize,

        /** 结果列名，顺序与每行数据对应；可能为 null。 */
        List<String> columnList,

        /** 结果列的英文名或物理列名，可能为 null，也不保证与 {@code columnList} 等长。 */
        List<String> columnEnList,

        /** 按列名组织的行数据；正常 JDBC 成功路径非 null，元素 Map 为可变实现。 */
        List<Map<String, Object>> dataList,

        /**
         * 执行成功标志。null 表示上游未明确写入，不能自动拆箱；false 时通常应同时提供
         * {@code errorMessage}，但当前类型本身不强制该组合。
         */
        Boolean success,

        /** 失败原因；成功时为 null，失败时也可能缺失。 */
        String errorMessage,

        /** 是否命中缓存；true 表示结果来自缓存，false 表示本次执行或上游未标记缓存。 */
        boolean fromCache,

        /** 查询开始时间，Unix epoch 毫秒；不是相对耗时。 */
        Long queryStartTime,

        /** 查询结果物化完成时间，Unix epoch 毫秒；失败或未完成时可能为 null。 */
        Long queryEndTime,

        /** 从 {@code queryStartTime} 到获取到数据库连接的耗时，单位毫秒；未获取连接时可能为 null。 */
        Long createConnectionTime,

        /**
         * 连接包装或鉴权阶段耗时，单位毫秒。当前 JDBC provider 未赋值，具体 provider
         * 填充前应视为 null，不能据此参与耗时计算。
         */
        Long wrapAuthTime,

        /**
         * 数据源服务端开始处理时间或耗时诊断字段。当前 JDBC provider 未赋值，
         * 单位与时间基准由后续具体 provider 明确，当前消费方不得假定其含义。
         */
        Long startInServer,

        /**
         * 数据源服务端结束处理时间或耗时诊断字段。当前 JDBC provider 未赋值，
         * 单位与时间基准由后续具体 provider 明确，当前消费方不得假定其含义。
         */
        Long endInServer) {
}
