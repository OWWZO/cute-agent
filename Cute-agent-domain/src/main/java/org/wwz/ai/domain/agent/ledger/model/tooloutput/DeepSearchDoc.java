package org.wwz.ai.domain.agent.ledger.model.tooloutput;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * deep_search 文档摘要。
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
public class DeepSearchDoc {

    private String title;

    private String link;

    private String summary;

    private String docType;

    public DeepSearchDoc(String title, String link, String summary) {
        this(title, link, summary, null);
    }

    /**
     * 统一工厂，避免运行时依赖 Lombok Builder 内部类。
     */
    public static DeepSearchDoc of(String title, String link, String summary) {
        return of(title, link, summary, null);
    }

    public static DeepSearchDoc of(String title, String link, String summary, String docType) {
        return new DeepSearchDoc(title, link, summary, docType);
    }
}
