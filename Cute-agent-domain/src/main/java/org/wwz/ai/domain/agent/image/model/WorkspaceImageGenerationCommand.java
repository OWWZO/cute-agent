package org.wwz.ai.domain.agent.image.model;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * 生图工作台生成命令。
 * <p>{@link #fileNames} / {@link #maskFileNames} 按引用持有，不做防御性拷贝；
 * 命令交给异步执行路径后，调用方不得再修改传入的列表。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class WorkspaceImageGenerationCommand {
    /** 批次键；为空时由工作台服务生成 UUID。 */
    private String requestId;
    /** 生成提示词；不能为空白。 */
    private String prompt;
    /** 模式：{@code images}（文生图）或 {@code edits}（图生图）；其他值按是否带参考图推断。 */
    private String mode;
    /**
     * 参考图列表（图生图输入）：支持 http(s) URL、data URL、{@code /preview/} 或
     * {@code /download/} 相对路径，以及文件名（按当前 requestId 解析为预览地址）。
     */
    private List<String> fileNames;
    /**
     * 蒙版图列表：与 {@link #fileNames} 按下标 1:1 对齐，空字符串表示该位没有蒙版；
     * 数量不得超过 {@code fileNames}。
     */
    private List<String> maskFileNames;
    /** 输出文件基础名（不含扩展名）；工作台服务会清洗特殊字符，为空时使用默认名。 */
    private String fileName;
    /** 输出文件描述；为空时取 prompt 截断生成。 */
    private String fileDescription;
    /** 模型名；当前工作台链路不读取该字段，网关固定使用后端配置的模型。 */
    private String model;
    /** 输出尺寸（如 {@code 1024x1024}）；为空时使用默认值。 */
    private String size;
    /** 生成张数；工作台服务钳制到 1..10。 */
    private Integer n;
}
