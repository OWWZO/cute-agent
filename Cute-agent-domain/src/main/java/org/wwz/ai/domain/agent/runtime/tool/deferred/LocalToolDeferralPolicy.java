package org.wwz.ai.domain.agent.runtime.tool.deferred;

import org.wwz.ai.domain.agent.runtime.tool.common.dataprep.DataAggregateTool;
import org.wwz.ai.domain.agent.runtime.tool.common.dataprep.DataCleanTool;
import org.wwz.ai.domain.agent.runtime.tool.common.dataprep.DataMergeTool;
import org.wwz.ai.domain.agent.runtime.tool.common.dataprep.DataTransformTool;
import org.wwz.ai.domain.agent.runtime.tool.common.dataprep.DataValidateTool;
import org.wwz.ai.domain.agent.runtime.tool.common.dataprep.SqlQueryTool;
import org.wwz.ai.domain.agent.runtime.tool.common.docread.CitationExtractorTool;
import org.wwz.ai.domain.agent.runtime.tool.common.docread.CsvProcessorTool;
import org.wwz.ai.domain.agent.runtime.tool.common.docread.ExcelReaderTool;
import org.wwz.ai.domain.agent.runtime.tool.common.docread.HtmlProcessorTool;
import org.wwz.ai.domain.agent.runtime.tool.common.docread.MarkdownProcessorTool;
import org.wwz.ai.domain.agent.runtime.tool.common.docread.PdfReaderTool;
import org.wwz.ai.domain.agent.runtime.tool.common.docread.PdfStructureTool;
import org.wwz.ai.domain.agent.runtime.tool.common.docread.TextProcessorTool;
import org.wwz.ai.domain.agent.runtime.tool.common.DeepSearchTool;
import org.wwz.ai.domain.agent.runtime.tool.common.DataAnalysisTool;
import org.wwz.ai.domain.agent.runtime.tool.common.docgen.ChecklistGenerateTool;
import org.wwz.ai.domain.agent.runtime.tool.common.docgen.ChartGeneratorTool;
import org.wwz.ai.domain.agent.runtime.tool.common.docgen.DocumentGenerateTool;
import org.wwz.ai.domain.agent.runtime.tool.common.docgen.DocumentTemplateTool;
import org.wwz.ai.domain.agent.runtime.tool.common.docgen.ExcelGeneratorTool;
import org.wwz.ai.domain.agent.runtime.tool.common.docgen.SlidesGenerateTool;
import org.wwz.ai.domain.agent.runtime.tool.common.docgen.TemplateFillerTool;
import org.wwz.ai.domain.agent.runtime.tool.common.docgen.ThemeDesignerTool;

import java.util.Set;

/**
 * 默认的本地工具延迟候选名单。
 * 只有实际装配进当前 ToolCollection 的工具才会被移动到 catalog。
 * <p>名字全部取自各工具类的 {@code TOOL_NAME} 常量，
 * 工具改名时这里会编译失败而不是静默漂移。</p>
 */
public final class LocalToolDeferralPolicy {

    public static final Set<String> DEFERRED_LOCAL_TOOL_NAMES = Set.of(
            ChartGeneratorTool.TOOL_NAME,
            ChecklistGenerateTool.TOOL_NAME,
            DocumentGenerateTool.TOOL_NAME,
            DocumentTemplateTool.TOOL_NAME,
            ExcelGeneratorTool.TOOL_NAME,
            SlidesGenerateTool.TOOL_NAME,
            TemplateFillerTool.TOOL_NAME,
            ThemeDesignerTool.TOOL_NAME,
            CitationExtractorTool.TOOL_NAME,
            CsvProcessorTool.TOOL_NAME,
            ExcelReaderTool.TOOL_NAME,
            HtmlProcessorTool.TOOL_NAME,
            MarkdownProcessorTool.TOOL_NAME,
            PdfReaderTool.TOOL_NAME,
            PdfStructureTool.TOOL_NAME,
            TextProcessorTool.TOOL_NAME,
            DataAggregateTool.TOOL_NAME,
            DataCleanTool.TOOL_NAME,
            DataMergeTool.TOOL_NAME,
            DataTransformTool.TOOL_NAME,
            DataValidateTool.TOOL_NAME,
            SqlQueryTool.TOOL_NAME,
            DeepSearchTool.TOOL_NAME,
            DataAnalysisTool.TOOL_NAME
    );

    private LocalToolDeferralPolicy() {
    }

    public static boolean isCandidate(String name) {
        return name != null && DEFERRED_LOCAL_TOOL_NAMES.contains(name);
    }
}
