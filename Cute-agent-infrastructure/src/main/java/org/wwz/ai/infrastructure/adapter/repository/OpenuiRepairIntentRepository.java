package org.wwz.ai.infrastructure.adapter.repository;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import org.wwz.ai.domain.agent.adapter.repository.IOpenUiRepairIntentRepository;
import org.wwz.ai.domain.agent.runtime.openui.OpenUiRepairIntent;
import org.wwz.ai.infrastructure.dao.IOpenuiRepairIntentDao;
import org.wwz.ai.infrastructure.dao.po.OpenuiRepairIntentPO;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * 意图仓储实现：覆盖式保存（先删后插），读取按 intent_index 升序。
 */
@Slf4j
@Repository
@RequiredArgsConstructor
public class OpenuiRepairIntentRepository implements IOpenUiRepairIntentRepository {

    private static final int BATCH_SIZE = 500;

    private final IOpenuiRepairIntentDao repairIntentDao;

    @Override
    @Transactional
    public void saveAll(String sessionId, String messageId, String programHash,
                        List<OpenUiRepairIntent> intents, String model) {
        if (sessionId == null || messageId == null || programHash == null) {
            throw new IllegalArgumentException("保存意图缺少 key 三元组");
        }
        List<OpenUiRepairIntent> safeIntents = intents == null ? List.of() : intents;
        repairIntentDao.deleteByProgram(sessionId, messageId, programHash);
        LocalDateTime now = LocalDateTime.now();
        List<OpenuiRepairIntentPO> rows = new ArrayList<>(safeIntents.size());
        for (int i = 0; i < safeIntents.size(); i++) {
            OpenUiRepairIntent intent = safeIntents.get(i);
            if (intent == null || intent.searchText().isBlank()) {
                continue;
            }
            rows.add(OpenuiRepairIntentPO.builder()
                    .sessionId(sessionId)
                    .messageId(messageId)
                    .programHash(programHash)
                    .intentIndex(i)
                    .searchText(intent.searchText())
                    .replaceText(intent.replaceText())
                    .model(model)
                    .createdAt(now)
                    .build());
        }
        for (int start = 0; start < rows.size(); start += BATCH_SIZE) {
            int end = Math.min(start + BATCH_SIZE, rows.size());
            repairIntentDao.insertBatch(rows.subList(start, end));
        }
    }

    @Override
    public List<OpenUiRepairIntent> listByProgram(String sessionId, String messageId, String programHash) {
        if (sessionId == null || messageId == null || programHash == null) {
            return List.of();
        }
        List<OpenuiRepairIntentPO> rows = repairIntentDao.listByProgram(sessionId, messageId, programHash);
        if (rows == null || rows.isEmpty()) {
            return List.of();
        }
        List<OpenUiRepairIntent> result = new ArrayList<>(rows.size());
        for (OpenuiRepairIntentPO row : rows) {
            if (row == null || row.getSearchText() == null) {
                continue;
            }
            result.add(new OpenUiRepairIntent(row.getSearchText(), row.getReplaceText()));
        }
        return result;
    }
}
