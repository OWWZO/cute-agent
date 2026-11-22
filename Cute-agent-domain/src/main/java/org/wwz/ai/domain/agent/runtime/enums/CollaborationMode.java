package org.wwz.ai.domain.agent.runtime.enums;

import org.apache.commons.lang3.StringUtils;

/**
 * 用户可见的主协作入口。
 *
 * <p>普通模式由用户选择一个业务 Agent；集群模式由通用协调者接管主会话。
 * 两者互斥，不是可自由组合的两个维度。</p>
 */
public enum CollaborationMode {

    NORMAL("normal"),
    CLUSTER("cluster");

    public static final String NORMAL_VALUE = "normal";
    public static final String CLUSTER_VALUE = "cluster";

    private final String value;

    CollaborationMode(String value) {
        this.value = value;
    }

    public String getValue() {
        return value;
    }

    public boolean isCluster() {
        return this == CLUSTER;
    }

    public static CollaborationMode from(String value) {
        String normalized = StringUtils.trimToEmpty(value).toLowerCase();
        if (CLUSTER_VALUE.equals(normalized)) {
            return CLUSTER;
        }
        if (NORMAL_VALUE.equals(normalized) || normalized.isEmpty()) {
            return NORMAL;
        }
        throw new IllegalArgumentException("collaborationMode 必须是 normal 或 cluster: " + value);
    }

    public static CollaborationMode fromLegacyDeepThink(Integer deepThink) {
        return deepThink != null && deepThink != 0 ? CLUSTER : NORMAL;
    }

    public static String normalize(String value) {
        return from(value).getValue();
    }
}
