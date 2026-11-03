package org.wwz.ai.application.agent.run;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Service;
import org.wwz.ai.application.agent.askuser.AskUserQuestionApplicationService;
import org.wwz.ai.application.agent.planmode.PlanApprovalApplicationService;
import org.wwz.ai.domain.agent.runtime.desktopcontrol.IDesktopControlRepository;

import javax.annotation.Resource;

/**
 * 会话是否存在未决的人机交互（提问 / 计划审批 / 桌面控制）。
 * <p>任一为真时后台任务通知只排队，由 resume 轮第一步 drain，不主动唤醒。</p>
 */
@Slf4j
@Service
public class SessionYieldGate {

    @Resource
    private AskUserQuestionApplicationService askUserQuestionApplicationService;

    @Resource
    private PlanApprovalApplicationService planApprovalApplicationService;

    @Resource
    private IDesktopControlRepository desktopControlRepository;

    public boolean hasOpenYield(String sessionId) {
        if (StringUtils.isBlank(sessionId)) {
            return false;
        }
        try {
            if (askUserQuestionApplicationService != null
                    && askUserQuestionApplicationService.hasOpenQuestion(sessionId)) {
                return true;
            }
            if (planApprovalApplicationService != null
                    && planApprovalApplicationService.hasOpenApproval(sessionId)) {
                return true;
            }
            return desktopControlRepository != null
                    && desktopControlRepository.hasOpenBySessionId(sessionId);
        } catch (Exception e) {
            // 查询失败时按「仍在等待用户」处理，宁可晚一点唤醒，也不要打断 HITL。
            log.warn("session yield check failed sessionId={}", sessionId, e);
            return true;
        }
    }
}
