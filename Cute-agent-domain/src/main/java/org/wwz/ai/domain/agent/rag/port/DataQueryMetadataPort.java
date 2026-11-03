package org.wwz.ai.domain.agent.rag.port;

import org.wwz.ai.domain.agent.rag.model.schema.DataQueryTableColumn;

import java.util.List;

/**
 * 数据查询元信息端口。
 * 用于按数据源读取表字段定义或 SQL 结果字段定义，避免 domain 直接依赖 JDBC 元信息读取器。
 *
 * <h2>安全契约</h2>
 * <ul>
 *   <li>{@link #getTableColumnsOfSql} 的 {@code sql} 来自可配置内容，调用方必须先校验为
 *       单条只读 {@code SELECT}；实现方不得把 DDL/DML/多语句原样执行。</li>
 *   <li>实现方只能读取 {@code ResultSetMetaData}，不得把结果行物化后返回。</li>
 * </ul>
 */
public interface DataQueryMetadataPort {

    /**
     * 读取表字段定义。
     *
     * @return 字段列表；表不存在或无权限时返回空列表，不返回 {@code null}
     */
    List<DataQueryTableColumn> queryColumns(String tableName, String schema);

    /**
     * 读取 SQL 结果集字段定义（只读探测，不消费数据行）。
     * <p>{@code limit} 是数据源侧的最大读取行数/游标提示：{@code limit > 0} 时实现必须按该值
     * 设置行数上限；{@code limit <= 0} 时使用实现默认上限。由于只读元数据，正常不会真正
     * 拉取数据行，但上限仍用于防止驱动预取过多数据。</p>
     *
     * @param sql   只读 SELECT；调用方已校验
     * @param limit 最大读取行数提示，{@code <= 0} 表示使用实现默认值
     * @return 字段列表；SQL 无结果列时返回空列表，不返回 {@code null}
     */
    List<DataQueryTableColumn> getTableColumnsOfSql(String sql, int limit);
}
