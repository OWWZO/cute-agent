package org.wwz.ai.domain.agent.adapter.port;

import org.wwz.ai.domain.agent.model.valobj.ConversationUploadedFile;

import java.io.InputStream;

/**
 * 会话附件上传端口。
 * domain/case 只表达上传语义，具体转发到 cute-tool 文件服务由 infrastructure 承接。
 */
public interface ConversationFilePort {

    /**
     * 上传会话附件，返回稳定预览/下载地址与资源键。
     *
     * <p><b>流所有权</b>：{@code content} 归调用方所有，调用方负责关闭；
     * 实现只能读取，不得关闭该流。</p>
     *
     * <p>失败通过非受检异常表达（方法不声明受检异常）：</p>
     * <ul>
     *   <li>{@link IllegalArgumentException}——{@code sessionId} 为空白或 {@code content} 为 null；</li>
     *   <li>{@link IllegalStateException}——文件服务未配置，或调用失败（实现会把 I/O 异常
     *       包装成本异常）。</li>
     * </ul>
     *
     * @param sessionId        会话 ID，必填
     * @param originalFileName 原始文件名；为空白时实现回退为 {@code uploaded-file}
     * @param contentType      MIME 类型；为 null 时按 {@code application/octet-stream} 处理
     * @param size             文件字节数，作为 multipart 的内容长度提示
     * @param content          文件内容流，非 null
     * @return 非 null 的上传结果
     */
    ConversationUploadedFile upload(String sessionId,
                                    String originalFileName,
                                    String contentType,
                                    long size,
                                    InputStream content);
}
