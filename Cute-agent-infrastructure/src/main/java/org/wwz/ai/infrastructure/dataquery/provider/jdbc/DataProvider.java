package org.wwz.ai.infrastructure.dataquery.provider.jdbc;

import org.wwz.ai.infrastructure.dataquery.provider.jdbc.JdbcQueryRequest;
import org.wwz.ai.domain.agent.rag.model.query.SqlExecutionResult;

import java.sql.SQLException;

public interface DataProvider<T extends JdbcQueryRequest> {
    SqlExecutionResult queryData(T request) throws SQLException;

    /**
     * 申请并关闭一次连接以验证连通性。
     *
     * @return 成功取得连接时为 true；失败应抛异常而不是返回 false
     */
    boolean queryForTest(T request);
}
