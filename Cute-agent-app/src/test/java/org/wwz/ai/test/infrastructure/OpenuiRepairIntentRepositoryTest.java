package org.wwz.ai.test.infrastructure;

import org.junit.Assert;
import org.junit.Test;
import org.mockito.ArgumentCaptor;
import org.wwz.ai.domain.agent.runtime.openui.OpenUiRepairIntent;
import org.wwz.ai.infrastructure.adapter.repository.OpenuiRepairIntentRepository;
import org.wwz.ai.infrastructure.dao.IOpenuiRepairIntentDao;
import org.wwz.ai.infrastructure.dao.po.OpenuiRepairIntentPO;

import java.util.ArrayList;
import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

public class OpenuiRepairIntentRepositoryTest {

    @Test
    @SuppressWarnings("unchecked")
    public void saveAllUsesBoundedBatchInsertsAndKeepsOriginalIntentIndexes() {
        IOpenuiRepairIntentDao dao = mock(IOpenuiRepairIntentDao.class);
        List<OpenUiRepairIntent> intents = new ArrayList<>(501);
        for (int i = 0; i < 501; i++) {
            intents.add(new OpenUiRepairIntent("search-" + i, "replace-" + i));
        }

        new OpenuiRepairIntentRepository(dao)
                .saveAll("session-1", "message-1", "hash-1", intents, "model-1");

        ArgumentCaptor<List<OpenuiRepairIntentPO>> batches =
                ArgumentCaptor.forClass((Class) List.class);
        verify(dao, times(2)).insertBatch(batches.capture());
        Assert.assertEquals(500, batches.getAllValues().get(0).size());
        Assert.assertEquals(1, batches.getAllValues().get(1).size());
        Assert.assertEquals(Integer.valueOf(499), batches.getAllValues().get(0).get(499).getIntentIndex());
        Assert.assertEquals(Integer.valueOf(500), batches.getAllValues().get(1).get(0).getIntentIndex());
        verify(dao, never()).insert(any(OpenuiRepairIntentPO.class));
    }
}
