package org.wwz.ai.domain.agent.runtime.tool.common.dataprep;

import org.wwz.ai.domain.agent.runtime.tool.common.docread.AbstractDocReadTool;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 表格数据转换工具，支持重命名、类型转换、值映射、派生列和列选择等操作。
 */
public class DataTransformTool extends AbstractDocReadTool {

    public static final String TOOL_NAME = "data_transform";

    @Override
    public String getName() {
        return TOOL_NAME;
    }

    @Override
    protected String endpointPath() {
        return "/v1/tool/data_transform";
    }

    @Override
    protected String defaultDescription() {
        return "Transform tabular data: rename columns, cast types, map values, "
                + "derive columns, string upper/lower/strip. Pass transformations=[{type, ...}] "
                + "(operations is accepted as alias).";
    }

    @Override
    protected Map<String, Object> defaultParams() {
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("transformations", Map.of(
                "type", "array",
                "description", "Transform ops: rename, cast, map_values, derive, select, reorder, drop, string_transform, ...",
                "items", objectProp("Transform operation")
        ));
        properties.put("operations", Map.of(
                "type", "array",
                "description", "Alias of transformations",
                "items", objectProp("Transform operation")
        ));
        properties.put("data", Map.of("type", "array", "items", objectProp("Row object")));
        properties.put("source_path", stringProp("Input file path under workspace"));
        properties.put("output_format", stringProp("records | dict"));
        Map<String, Object> schema = objectSchema(properties, List.of());
        // 至少要有一个转换操作，否则调用没有意义（输入可来自 data/source_path/artifact）。
        schema.put("anyOf", List.of(
                Map.of("required", List.of("transformations")),
                Map.of("required", List.of("operations"))));
        return schema;
    }
}
