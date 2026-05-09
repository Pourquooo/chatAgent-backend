package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.entity.AgentTrace;
import com.kama.jchatmind.model.response.GetTraceDetailResponse;
import com.kama.jchatmind.service.TraceService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class TraceController {

    private final TraceService traceService;

    // 按会话列出所有 trace（最新在前），用于前端选择某次运行查看详情
    @GetMapping("/traces/session/{sessionId}")
    public ApiResponse<List<AgentTrace>> getTracesBySessionId(@PathVariable String sessionId) {
        return ApiResponse.success(traceService.getTracesBySessionId(sessionId));
    }

    // 查看单条 trace 的完整链路（含 steps + tool_calls）
    @GetMapping("/traces/{traceId}")
    public ApiResponse<GetTraceDetailResponse> getTraceDetail(@PathVariable String traceId) {
        AgentTrace trace = traceService.getTraceById(traceId);
        if (trace == null) {
            return ApiResponse.error("trace not found: " + traceId);
        }
        GetTraceDetailResponse resp = GetTraceDetailResponse.builder()
                .trace(trace)
                .steps(traceService.getStepsByTraceId(traceId))
                .toolCalls(traceService.getToolCallsByTraceId(traceId))
                .build();
        return ApiResponse.success(resp);
    }
}
