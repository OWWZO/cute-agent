package org.wwz.ai.infrastructure.dao.cute.po;

import lombok.Data;

import java.time.LocalDateTime;

@Data
public class SessionTodoPO {
    private Long id;
    private String sessionId;
    private String taskId;
    private String subject;
    private String description;
    private String activeForm;
    private String status;
    private Integer seqNo;
    private LocalDateTime createTime;
    private LocalDateTime updateTime;
    private Integer deleted;
}
