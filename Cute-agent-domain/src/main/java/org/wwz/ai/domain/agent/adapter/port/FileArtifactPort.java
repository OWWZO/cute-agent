package org.wwz.ai.domain.agent.adapter.port;

import org.wwz.ai.domain.agent.runtime.dto.FileRequest;
import org.wwz.ai.domain.agent.runtime.dto.FileResponse;

import java.io.IOException;

/**
 * 文件产物端口。
 * domain 只表达“上传文件 / 查询文件 / 读取文件内容”，由 infrastructure 承接具体文件服务协议。
 *
 * <p>失败契约（与当前 infrastructure 适配器一致）：</p>
 * <ul>
 *   <li>返回值可能为 null：{@link #upload} / {@link #get} 在响应体为空时返回 null；
 *       {@link #register} / {@link #uploadLocalFile} 在响应体为空或不是合法 JSON 时返回 null；</li>
     *   <li>网络失败、远端非 2xx、本地文件缺失或不在允许根目录内、超过上传/下载大小上限均抛 {@link IOException}，
 *       其中 {@link #readBytes(String, Long, long)} 的超限在流式读取阶段就会抛；</li>
 *   <li>“文件不存在”没有独立信号：远端 2xx 但缺少 URL 字段时返回 ossUrl 为 null 的
 *       {@link FileResponse}，调用方必须自行判空，不能只看是否抛异常。</li>
 * </ul>
 */
public interface FileArtifactPort {

    /**
     * 上传文本文件到远端文件服务。
     */
    FileResponse upload(String serviceBaseUrl, FileRequest request) throws IOException;

    /**
     * 登记本地已有文件（不传 content），返回预览/下载 URL。
     * 只在 Java 后端与文件服务共享同一份 workspace 路径时成立。
     */
    FileResponse register(String serviceBaseUrl, FileRequest request) throws IOException;

    /**
     * 以 multipart 流式上传本地文件二进制内容，返回与 {@link #register} 同形的响应。
     * 用于 Java 后端与文件服务不共享文件系统的部署。
     */
    FileResponse uploadLocalFile(String serviceBaseUrl, FileRequest request) throws IOException;

    /**
     * 物化本地文件的统一入口：共享卷部署走 {@link #register} 零拷贝，
     * 非共享部署由适配器改为 {@link #uploadLocalFile}。工具层只需调用本方法，
     * 不感知部署拓扑。
     * <p>分支由适配器配置决定，不会在 {@code register} 失败后自动回退：
     * 本地文件缺失或共享卷假设不成立时按底层调用失败抛出 {@link IOException}。
     * {@link #register} / {@link #uploadLocalFile} 是低层原语，直接调用即绕过本方法的拓扑处理。</p>
     */
    FileResponse materializeLocalFile(String serviceBaseUrl, FileRequest request) throws IOException;

    /**
     * 按文件名获取远端文件信息。
     */
    FileResponse get(String serviceBaseUrl, FileRequest request) throws IOException;

    /**
     * 读取指定 URL 的文本内容。
     * <p>仅适合文本文件；二进制请用 {@link #readBytes(String, Long)}。
     */
    String readText(String url, Long timeoutSeconds) throws IOException;

    /**
     * 读取指定 URL 的原始字节（xlsx/pdf/图片等二进制附件必须走此接口）。
     */
    byte[] readBytes(String url, Long timeoutSeconds) throws IOException;

    /** 读取原始字节，并在流式读取阶段限制最大响应大小。 */
    byte[] readBytes(String url, Long timeoutSeconds, long maxBytes) throws IOException;
}
