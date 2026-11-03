package org.wwz.ai.domain.agent.runtime.agent;

import lombok.Data;
import lombok.experimental.Accessors;
import org.wwz.ai.domain.agent.ledger.model.replay.ReplayTiming;
import org.wwz.ai.domain.agent.ledger.model.tooloutput.ToolStructuredOutput;

/**
 * 单次工具执行结果（展示、记忆写回、账本共用）。
 * <p>工厂方法不填充 {@link #timing}：它在 {@code ToolExecutionPipeline.settle(...)} 中由
 * {@code ToolTimingTracker.attachFinished(...)} 补写。绕过 settle 直接消费 outcome 的代码
 * 必须自行确认 timing 已附加，否则读到 null。</p>
 * <p>不变量：{@code success=true} 时 {@code errorMsg} 应为 null；{@code success=false} 时
 * 应携带非空 errorMsg（用户停止以 {@code USER_STOP} 编码）。下游的 isUserStop/ledgerStatus
 * 依赖这一配对，构造方需保持一致。</p>
 */
@Data
@Accessors(chain = true)
public class ToolExecutionOutcome {
    private boolean success;
    private String toolResult;
    private String llmObservation;
    private ToolStructuredOutput structuredOutput;
    private String errorMsg;
    private String base64Image;
    private String imageMimeType;
    private ReplayTiming timing;

    static ToolExecutionOutcome success(String toolResult,
                                        String llmObservation,
                                        ToolStructuredOutput structuredOutput,
                                        String base64Image,
                                        String imageMimeType) {
        return new ToolExecutionOutcome()
                .setSuccess(true)
                .setToolResult(toolResult)
                .setLlmObservation(llmObservation)
                .setStructuredOutput(structuredOutput)
                .setBase64Image(base64Image)
                .setImageMimeType(imageMimeType);
    }

    static ToolExecutionOutcome failure(String toolResult,
                                        String llmObservation,
                                        ToolStructuredOutput structuredOutput,
                                        String errorMsg) {
        return new ToolExecutionOutcome()
                .setSuccess(false)
                .setToolResult(toolResult)
                .setLlmObservation(llmObservation)
                .setStructuredOutput(structuredOutput)
                .setErrorMsg(errorMsg);
    }
}
