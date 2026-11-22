package org.wwz.ai.trigger.http.agent;

import com.alibaba.fastjson.JSONObject;
import lombok.Data;
import org.apache.commons.lang3.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.wwz.ai.api.response.Response;
import org.wwz.ai.application.agent.authorization.SessionOwnershipDeniedException;
import org.wwz.ai.application.agent.review.TaskReviewApplicationService;
import org.wwz.ai.types.agent.user.UserRequestContext;
import org.wwz.ai.types.enums.ResponseCode;

import javax.annotation.Resource;
import java.util.List;
import java.util.Map;

/**
 * 单次任务复盘入口。只读导出，不修改 Execution Ledger。
 */
@RestController
@RequestMapping("/api/agent/task-review")
public class AgentTaskReviewController {

    @Resource
    private TaskReviewApplicationService taskReviewApplicationService;

    @PostMapping("/preflight")
    public Response<Map<String, Object>> preflight(@RequestBody TaskReviewRequest request) {
        try {
            validateRequest(request);
            Map<String, Object> data = taskReviewApplicationService.preflight(
                    UserRequestContext.requireUserId(),
                    request.getSessionId(),
                    request.getRequestIds()
            );
            return success(data);
        } catch (SessionOwnershipDeniedException e) {
            return forbidden(e.getMessage());
        } catch (IllegalArgumentException e) {
            return illegal(e.getMessage());
        } catch (Exception e) {
            return error(e);
        }
    }

    @PostMapping("/traces")
    public Response<JSONObject> traces(@RequestBody TaskReviewRequest request) {
        try {
            validateRequest(request);
            JSONObject data = taskReviewApplicationService.exportBundle(
                    UserRequestContext.requireUserId(),
                    request.getSessionId(),
                    request.getRequestIds()
            );
            return Response.<JSONObject>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(data)
                    .build();
        } catch (SessionOwnershipDeniedException e) {
            return response(ResponseCode.FORBIDDEN.getCode(), e.getMessage());
        } catch (IllegalArgumentException e) {
            return response(ResponseCode.ILLEGAL_PARAMETER.getCode(), e.getMessage());
        } catch (Exception e) {
            return response(ResponseCode.UN_ERROR.getCode(), e.getMessage());
        }
    }

    @PostMapping("/tree-traces")
    public Response<JSONObject> treeTraces(@RequestBody TaskReviewRequest request) {
        try {
            validateSession(request);
            JSONObject data = taskReviewApplicationService.exportTreeBundle(
                    UserRequestContext.requireUserId(),
                    request.getSessionId()
            );
            return Response.<JSONObject>builder()
                    .code(ResponseCode.SUCCESS.getCode())
                    .info(ResponseCode.SUCCESS.getInfo())
                    .data(data)
                    .build();
        } catch (SessionOwnershipDeniedException e) {
            return response(ResponseCode.FORBIDDEN.getCode(), e.getMessage());
        } catch (IllegalArgumentException e) {
            return response(ResponseCode.ILLEGAL_PARAMETER.getCode(), e.getMessage());
        } catch (Exception e) {
            return response(ResponseCode.UN_ERROR.getCode(), e.getMessage());
        }
    }

    @PostMapping("/model-config")
    public Response<Map<String, Object>> modelConfig(@RequestBody TaskReviewRequest request) {
        try {
            validateSession(request);
            return success(taskReviewApplicationService.modelConfig(
                    UserRequestContext.requireUserId(),
                    request.getSessionId()
            ));
        } catch (SessionOwnershipDeniedException e) {
            return forbidden(e.getMessage());
        } catch (IllegalArgumentException e) {
            return illegal(e.getMessage());
        } catch (Exception e) {
            return error(e);
        }
    }

    private void validateRequest(TaskReviewRequest request) {
        validateSession(request);
        if (request.getRequestIds() == null || request.getRequestIds().isEmpty()) {
            throw new IllegalArgumentException("requestIds 不能为空");
        }
    }

    private void validateSession(TaskReviewRequest request) {
        if (request == null || StringUtils.isBlank(request.getSessionId())) {
            throw new IllegalArgumentException("sessionId 不能为空");
        }
    }

    private Response<Map<String, Object>> success(Map<String, Object> data) {
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.SUCCESS.getCode())
                .info(ResponseCode.SUCCESS.getInfo())
                .data(data)
                .build();
    }

    private Response<Map<String, Object>> forbidden(String message) {
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.FORBIDDEN.getCode())
                .info(message)
                .build();
    }

    private Response<Map<String, Object>> illegal(String message) {
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.ILLEGAL_PARAMETER.getCode())
                .info(message)
                .build();
    }

    private Response<Map<String, Object>> error(Exception error) {
        return Response.<Map<String, Object>>builder()
                .code(ResponseCode.UN_ERROR.getCode())
                .info(error.getMessage())
                .build();
    }

    private <T> Response<T> response(String code, String info) {
        return Response.<T>builder()
                .code(code)
                .info(info)
                .build();
    }

    @Data
    public static class TaskReviewRequest {
        private String sessionId;
        private List<String> requestIds;
    }
}
