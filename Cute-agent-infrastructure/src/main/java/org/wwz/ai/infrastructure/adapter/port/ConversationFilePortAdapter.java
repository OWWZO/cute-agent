package org.wwz.ai.infrastructure.adapter.port;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.wwz.ai.domain.agent.adapter.port.ConversationFilePort;
import org.wwz.ai.domain.agent.model.valobj.ConversationUploadedFile;
import org.wwz.ai.infrastructure.gateway.CuteFileGateway;
import org.wwz.ai.infrastructure.gateway.dto.ConversationUploadFileDTO;

import java.io.InputStream;

/**
 * 会话附件上传端口适配：转发到 CuteFileGateway。
 */
@Component
@RequiredArgsConstructor
public class ConversationFilePortAdapter implements ConversationFilePort {

    private final CuteFileGateway cuteFileGateway;

    /**
     * 上传会话附件。
     *
     * <p>调用方保留 {@code content} 的所有权，并负责在方法返回后关闭该流；
     * 本适配器不会关闭它，也不会在失败时接管它的生命周期。</p>
     */
    @Override
    public ConversationUploadedFile upload(String sessionId,
                                           String originalFileName,
                                           String contentType,
                                           long size,
                                           InputStream content) {
        ConversationUploadFileDTO dto = cuteFileGateway.uploadConversationFile(
                sessionId, originalFileName, contentType, size, content);
        return ConversationUploadedFile.builder()
                .name(dto.getName())
                .url(dto.getUrl())
                .type(dto.getType())
                .size(dto.getSize())
                .downloadUrl(dto.getDownloadUrl())
                .previewUrl(dto.getPreviewUrl())
                .resourceKey(dto.getResourceKey())
                .mimeType(dto.getMimeType())
                .originFileName(dto.getOriginFileName())
                .build();
    }
}
