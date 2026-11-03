package org.wwz.ai.test.domain.rag;

import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;
import org.wwz.ai.domain.agent.adapter.port.AgentMessageStream;
import org.wwz.ai.domain.agent.rag.DataAgentQueryServiceImpl;
import org.wwz.ai.domain.agent.rag.Nl2SqlQueryService;
import org.wwz.ai.domain.agent.rag.TableRagService;
import org.wwz.ai.domain.agent.rag.model.chatmodel.ChatModelInfo;
import org.wwz.ai.domain.agent.rag.model.config.DataQuerySettings;
import org.wwz.ai.domain.agent.rag.model.query.DataAgentChatQuery;
import org.wwz.ai.domain.agent.rag.port.DataQueryExecutionPort;
import org.wwz.ai.domain.agent.rag.service.ChatModelInfoService;
import org.wwz.ai.domain.agent.rag.service.ChatModelSchemaService;
import org.wwz.ai.domain.agent.rag.service.SchemaRecallService;

import java.io.IOException;
import java.lang.reflect.Field;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * 问数 SSE 生命周期：启动阶段任何失败都必须给出终态帧，不能让客户端挂到超时。
 */
public class DataAgentQueryServiceImplChatQueryTest {

    private DataAgentQueryServiceImpl service;
    private AgentMessageStream stream;

    @Before
    public void setUp() throws Exception {
        DataQuerySettings settings = new DataQuerySettings();
        settings.getQdrantConfig().setEnable(false);
        settings.getEsConfig().setEnable(false);
        TableRagService tableRagService = mock(TableRagService.class);
        when(tableRagService.tableRag(any())).thenReturn(List.of());
        ChatModelInfoService chatModelInfoService = mock(ChatModelInfoService.class);
        ChatModelInfo model = new ChatModelInfo();
        model.setCode("m1");
        model.setType("table");
        model.setContent("t_m1");
        when(chatModelInfoService.listDistinctModels()).thenReturn(List.of(model));
        ChatModelSchemaService chatModelSchemaService = mock(ChatModelSchemaService.class);
        when(chatModelSchemaService.queryAllSchemas()).thenReturn(List.of());

        service = new DataAgentQueryServiceImpl(
                settings,
                tableRagService,
                chatModelInfoService,
                chatModelSchemaService,
                mock(Nl2SqlQueryService.class),
                mock(DataQueryExecutionPort.class),
                mock(SchemaRecallService.class));
        stream = mock(AgentMessageStream.class);
    }

    @Test
    public void debugSendFailureCompletesStreamWithError() throws Exception {
        doThrow(new IOException("client closed")).when(stream).send(any());

        service.chatQuery(new DataAgentChatQuery("销售额", null), stream);

        verify(stream).completeWithError(any(IOException.class));
        verify(stream, never()).complete();
    }

    @Test
    public void executorRejectionCompletesStreamWithError() throws Exception {
        setToolExecutor(command -> {
            throw new RejectedExecutionException("pool saturated");
        });

        service.chatQuery(new DataAgentChatQuery("销售额", null), stream);

        verify(stream).completeWithError(any(RuntimeException.class));
        verify(stream, never()).complete();
    }

    private void setToolExecutor(Executor executor) throws Exception {
        Field field = DataAgentQueryServiceImpl.class.getDeclaredField("toolExecutor");
        field.setAccessible(true);
        field.set(service, executor);
    }
}
