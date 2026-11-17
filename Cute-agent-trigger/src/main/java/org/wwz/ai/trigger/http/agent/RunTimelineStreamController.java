package org.wwz.ai.trigger.http.agent;

import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.wwz.ai.application.agent.query.RunTimelineQueryService;
import org.wwz.ai.application.agent.query.RunTimelineSnapshot;
import org.wwz.ai.types.agent.user.UserRequestContext;

/**
 * 主 Agent 与子 Agent 共用的 run 级 canonical 时间线观察入口。
 */
@RestController
@RequestMapping("/api/agent/runs")
@RequiredArgsConstructor
public class RunTimelineStreamController {

    private final RunTimelineQueryService runTimelineQueryService;
    private final RunTimelineStreamService runTimelineStreamService;

    @GetMapping(value = "/{requestId}/timeline/stream",
            produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public ResponseEntity<SseEmitter> stream(
            @PathVariable String requestId,
            @RequestParam(required = false) Long afterSeq,
            @RequestHeader(value = "Last-Event-ID", required = false) String lastEventIdHeader) {
        long from = AgentRunController.resolveLastEventSeq(afterSeq, lastEventIdHeader);
        RunTimelineSnapshot initial = runTimelineQueryService.query(
                UserRequestContext.requireUserId(), requestId, from);
        if (initial == null) {
            return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
        }
        return ResponseEntity.ok(runTimelineStreamService.open(
                UserRequestContext.requireUserId(), requestId, initial, from));
    }
}
