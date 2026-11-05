package org.wwz.ai.application.catalog.model;

import org.junit.Assert;
import org.junit.Test;
import org.wwz.ai.application.catalog.ApplicationResult;
import org.wwz.ai.domain.agent.catalog.model.AiClientModelConfig;
import org.wwz.ai.domain.agent.catalog.model.CatalogModel;
import org.wwz.ai.domain.agent.catalog.model.ModelConnectionTestResult;
import org.wwz.ai.domain.agent.catalog.port.IAiClientModelConfigRepository;
import org.wwz.ai.domain.agent.catalog.port.ICatalogModelRuntimePort;
import org.wwz.ai.domain.agent.catalog.port.IModelConnectionTestPort;

import java.util.List;
import java.util.Optional;

public class ModelConfigApplicationServiceTest {

    @Test
    public void shouldRejectDuplicateModelIdBeforeAdminInsert() {
        Repository repository = new Repository();
        repository.existing = AiClientModelConfig.builder().modelId("model-1").build();
        repository.matches = List.of(repository.existing);
        ModelConfigApplicationService service = new ModelConfigApplicationService(
                repository, new Runtime(), new ConnectionTest());

        ApplicationResult<Boolean> result = service.create(new ModelConfigCommand(
                null, "model-1", "api-1", "Model", "openai", "default", 0, 4096, 1));

        Assert.assertEquals("0002", result.code());
        Assert.assertFalse(result.data());
        Assert.assertNull(repository.inserted);
    }

    @Test
    public void shouldRejectChangingModelIdToAnotherRecordsId() {
        Repository repository = new Repository();
        repository.existing = AiClientModelConfig.builder().id(2L).modelId("model-b").build();
        repository.matches = List.of(AiClientModelConfig.builder().id(1L).modelId("model-a").build());
        ModelConfigApplicationService service = new ModelConfigApplicationService(
                repository, new Runtime(), new ConnectionTest());

        ApplicationResult<Boolean> result = service.updateById(new ModelConfigCommand(
                2L, "model-a", "api-1", "Model B", "openai", "default", 0, 4096, 1));

        Assert.assertEquals("0002", result.code());
        Assert.assertNull(repository.updated);
    }

    private static final class Runtime implements ICatalogModelRuntimePort {
        @Override public List<CatalogModel> listUserSelectableModels() { return List.of(); }
        @Override public void invalidateAll() { }
    }

    private static final class ConnectionTest implements IModelConnectionTestPort {
        @Override public ModelConnectionTestResult test(String modelReference) { return new ModelConnectionTestResult(true, 1, "ok"); }
        @Override public ModelConnectionTestResult testByRecordId(Long id) { return new ModelConnectionTestResult(true, 1, "ok"); }
    }

    private static final class Repository implements IAiClientModelConfigRepository {
        private AiClientModelConfig existing;
        private AiClientModelConfig inserted;
        private AiClientModelConfig updated;
        private List<AiClientModelConfig> matches = List.of();

        @Override public boolean insert(AiClientModelConfig config) { inserted = config; return true; }
        @Override public boolean updateById(AiClientModelConfig config) { updated = config; return true; }
        @Override public boolean updateByModelId(AiClientModelConfig config) { return true; }
        @Override public boolean deleteById(Long id) { return true; }
        @Override public boolean deleteByModelId(String modelId) { return true; }
        @Override public Optional<AiClientModelConfig> findById(Long id) { return Optional.empty(); }
        @Override public Optional<AiClientModelConfig> findByModelId(String modelId) { return Optional.ofNullable(existing); }
        @Override public List<AiClientModelConfig> findAllByModelId(String modelId) {
            return matches;
        }
        @Override public List<AiClientModelConfig> findByApiId(String apiId) { return List.of(); }
        @Override public List<AiClientModelConfig> findByModelType(String modelType) { return List.of(); }
        @Override public List<AiClientModelConfig> findEnabled(int limit) { return List.of(); }
        @Override public List<AiClientModelConfig> findAll(int offset, int limit) { return List.of(); }
    }
}
