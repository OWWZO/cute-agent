/**
 * Agent 仓储端口。
 * 负责声明持久化协作契约，禁止在 domain 中直接暴露 DAO / Mapper / SQL 等技术细节。
 * <p>本包只允许存放端口接口；实现必须位于 infrastructure 适配层。</p>
 */
package org.wwz.ai.domain.agent.adapter.repository;
