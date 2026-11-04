package org.wwz.ai.domain.agent.ledger.model.tooloutput;

import org.junit.Assert;
import org.junit.Test;

import java.util.ArrayList;
import java.util.List;

public class DeepSearchQueryResultTest {

    @Test
    public void constructorAndSetterNormalizeNullDocs() {
        DeepSearchQueryResult result = new DeepSearchQueryResult("query", null);
        Assert.assertTrue(result.getDocs().isEmpty());

        result.setDocs(null);
        Assert.assertTrue(result.getDocs().isEmpty());
    }

    @Test
    public void docsAreNotAliasedToCallerList() {
        List<DeepSearchDoc> docs = new ArrayList<>();
        docs.add(new DeepSearchDoc("first", null, null));
        DeepSearchQueryResult result = new DeepSearchQueryResult("query", docs);

        docs.clear();

        Assert.assertEquals(1, result.getDocs().size());
    }
}
