package org.wwz.ai.infrastructure.cute.service.impl;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.wwz.ai.domain.agent.memory.CompactionBudget;
import org.wwz.ai.domain.agent.memory.ltm.LtmManager;
import org.wwz.ai.domain.agent.memory.ltm.LtmServices;
import org.wwz.ai.domain.agent.memory.ltm.MemoryFlushPolicy;
import org.wwz.ai.domain.agent.memory.ltm.MemoryFlushService;
import org.wwz.ai.domain.agent.runtime.CuteRuntimeDependencies;
import org.wwz.ai.domain.agent.runtime.dto.Message;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

public class SessionContextCompactionLtmInputTest {

    private static final String MODEL_ONLY = "<task-notification>model-only-marker</task-notification>";
    private static final String NORMAL_ONE = "ordinary question";
    private static final String NORMAL_TWO = "ordinary follow-up";

    @Before
    public void clearGlobalLtmServices() {
        LtmServices.unbind();
    }

    @After
    public void cleanupGlobalLtmServices() {
        LtmServices.unbind();
    }

    @Test
    public void modelOnlyStaysInContextButNotInMemoryFlush() throws Exception {
        List<Message> flushed = new ArrayList<>();
        MemoryFlushService flushService = mock(MemoryFlushService.class);
        when(flushService.flushBeforeCompact(anyString(), anyString(), any(), anyList(), any(), any()))
                .thenAnswer(invocation -> {
                    flushed.addAll(invocation.getArgument(3));
                    return MemoryFlushService.FlushResult.ofSkipped();
                });
        List<Map<String, Object>> insightPayload = new ArrayList<>();
        LtmManager ltmManager = ltmManagerReturning("durable insight", insightPayload);
        CuteRuntimeDependencies dependencies = mock(CuteRuntimeDependencies.class);
        when(dependencies.getOptionalMemoryFlushService()).thenReturn(flushService);
        when(dependencies.getOptionalLtmManager()).thenReturn(ltmManager);

        List<Message> result = invokeHook(dependencies);

        assertContainsContent(result, MODEL_ONLY);
        assertContainsContent(result, NORMAL_ONE);
        assertContainsContent(result, NORMAL_TWO);
        assertContainsPrefix(result, "[memory-pre-compress] durable insight");
        assertEquals("LTM flush must receive only the two ordinary messages", 2, flushed.size());
        assertFalse("LTM flush must not receive the modelOnly message",
                containsContent(flushed, MODEL_ONLY));
        assertFalse("LTM insight must not receive the modelOnly message",
                containsPayloadContent(insightPayload, MODEL_ONLY));
    }

    @Test
    public void flushNudgeAndInsightStayInUnfilteredContext() throws Exception {
        List<Map<String, Object>> insightPayload = new ArrayList<>();
        LtmManager ltmManager = ltmManagerReturning("durable insight", insightPayload);
        CuteRuntimeDependencies dependencies = mock(CuteRuntimeDependencies.class);
        when(dependencies.resolveLtmFlushMinTurns()).thenReturn(1);
        when(dependencies.getOptionalLtmManager()).thenReturn(ltmManager);

        List<Message> result = invokeHook(dependencies);

        assertContainsContent(result, MODEL_ONLY);
        assertContainsContent(result, NORMAL_ONE);
        assertContainsContent(result, NORMAL_TWO);
        assertContainsPrefix(result, MemoryFlushPolicy.FLUSH_NOTE_PREFIX);
        assertContainsPrefix(result, "[memory-pre-compress] durable insight");
        assertTrue("LTM insight should include the flush nudge",
                containsPayloadContent(insightPayload, MemoryFlushPolicy.FLUSH_NOTE_PREFIX));
        assertFalse("LTM insight must not receive the modelOnly message",
                containsPayloadContent(insightPayload, MODEL_ONLY));
    }

    private static LtmManager ltmManagerReturning(String insight, List<Map<String, Object>> capturedPayload) {
        LtmManager ltmManager = mock(LtmManager.class);
        when(ltmManager.onPreCompress(anyList())).thenAnswer(invocation -> {
            capturedPayload.addAll(invocation.getArgument(0));
            return insight;
        });
        return ltmManager;
    }

    private static List<Message> invokeHook(CuteRuntimeDependencies dependencies) throws Exception {
        ObjectProvider<CuteRuntimeDependencies> provider = mock(ObjectProvider.class);
        when(provider.getObject()).thenReturn(dependencies);
        SessionContextCompactionServiceImpl service = new SessionContextCompactionServiceImpl(
                provider, null, null, CompactionBudget.defaults(), null);
        List<Message> input = List.of(
                Message.userMessage(NORMAL_ONE, null),
                Message.modelOnlyUserMessage(MODEL_ONLY),
                Message.userMessage(NORMAL_TWO, null));
        Method hook = SessionContextCompactionServiceImpl.class.getDeclaredMethod(
                "applyLtmPreCompactHooks", String.class, String.class, List.class);
        hook.setAccessible(true);
        @SuppressWarnings("unchecked")
        List<Message> result = (List<Message>) hook.invoke(service, "session-1", "request-1", input);
        return result;
    }

    private static void assertContainsContent(List<Message> messages, String expected) {
        assertTrue("missing message: " + expected, messages.stream()
                .anyMatch(message -> message != null && expected.equals(message.getContent())));
    }

    private static void assertContainsPrefix(List<Message> messages, String prefix) {
        assertTrue("missing message prefix: " + prefix, messages.stream()
                .anyMatch(message -> message != null
                        && message.getContent() != null
                        && message.getContent().startsWith(prefix)));
    }

    private static boolean containsContent(List<Message> messages, String expected) {
        return messages.stream().anyMatch(message -> message != null && expected.equals(message.getContent()));
    }

    private static boolean containsPayloadContent(List<Map<String, Object>> payload, String expected) {
        return payload.stream().anyMatch(row -> expected.equals(String.valueOf(row.get("content")))
                || String.valueOf(row.get("content")).startsWith(expected));
    }
}
