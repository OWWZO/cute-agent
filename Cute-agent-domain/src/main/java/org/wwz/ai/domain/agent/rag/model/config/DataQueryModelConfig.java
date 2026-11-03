package org.wwz.ai.domain.agent.rag.model.config;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/** A configured analytical model and its schema-generation rules. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class DataQueryModelConfig {
    private String name;
    private String id;
    private String type;
    private String content;
    private String remark;
    private String businessPrompt;
    /** Comma-separated column names excluded from schema generation; case-insensitive, blank means none. */
    private String ignoreFields;
    /** Comma-separated column names flagged as default recall fields; blank means none. */
    private String defaultRecallFields;
    /** Comma-separated column names flagged for analysis suggestions; blank means none. */
    private String analyzeSuggestFields;
    /** Comma-separated column names forbidden from analysis suggestions; blank means none. */
    private String analyzeForbidFields;
    /** Comma-separated column names whose distinct values are synced to the column-value index; blank means none. */
    private String syncValueFields;
    /** JSON object string mapping column name to its synonyms; blank means no aliases. */
    private String columnAliasMap;
}
