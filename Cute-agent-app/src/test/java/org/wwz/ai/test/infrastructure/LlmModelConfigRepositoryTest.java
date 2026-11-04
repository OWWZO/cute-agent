package org.wwz.ai.test.infrastructure;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.domain.agent.runtime.llm.LlmModelBinding;
import org.wwz.ai.infrastructure.adapter.repository.LlmModelConfigRepository;
import org.wwz.ai.infrastructure.dao.IAiClientApiDao;
import org.wwz.ai.infrastructure.dao.IAiClientModelDao;
import org.wwz.ai.infrastructure.dao.po.AiClientApi;
import org.wwz.ai.infrastructure.dao.po.AiClientModel;

import java.util.List;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

public class LlmModelConfigRepositoryTest {

    @Test
    public void listUsableLoadsEnabledApisOnceInsteadOfQueryingPerModel() {
        IAiClientModelDao modelDao = mock(IAiClientModelDao.class);
        IAiClientApiDao apiDao = mock(IAiClientApiDao.class);
        AiClientApi api = AiClientApi.builder()
                .apiId("api-1")
                .baseUrl("https://llm.example.test")
                .apiKey("secret")
                .status(1)
                .build();
        when(modelDao.queryEnabledModels()).thenReturn(List.of(
                model(1L, "model-a", "api-1"),
                model(2L, "model-b", "api-1")));
        when(apiDao.queryEnabledApis()).thenReturn(List.of(api));

        List<LlmModelBinding> bindings = new LlmModelConfigRepository(modelDao, apiDao).listUsable();

        Assert.assertEquals(2, bindings.size());
        Assert.assertEquals("model-a", bindings.get(0).getModelName());
        Assert.assertEquals("model-b", bindings.get(1).getModelName());
        verify(apiDao, times(1)).queryEnabledApis();
        verify(apiDao, never()).queryByApiId(anyString());
    }

    private AiClientModel model(Long id, String modelId, String apiId) {
        return AiClientModel.builder()
                .id(id)
                .modelId(modelId)
                .modelName(modelId)
                .apiId(apiId)
                .status(1)
                .build();
    }
}
