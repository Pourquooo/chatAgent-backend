package com.kama.jchatmind.service;

import com.kama.jchatmind.model.entity.AgentStepTrace;
import com.kama.jchatmind.model.entity.AgentTrace;
import com.kama.jchatmind.model.entity.ToolCallTrace;

import java.util.List;

/**
 * Agent 执行可观测性 - Trace 服务。
 *
 * 调用时序：
 *   startTrace(...)
 *     └─ startStep(...)
 *         ├─ startToolCall(...) / endToolCall(...)
 *         └─ endStep(...)
 *   endTrace(...)
 *
 * 写入操作对业务不透出异常：失败仅记录日志，避免 trace 故障影响 Agent 主流程。
 */
public interface TraceService {

    String STATUS_RUNNING  = "RUNNING";
    String STATUS_FINISHED = "FINISHED";
    String STATUS_SUCCESS  = "SUCCESS";
    String STATUS_ERROR    = "ERROR";

    String PHASE_THINK   = "THINK";
    String PHASE_EXECUTE = "EXECUTE";

    AgentTrace startTrace(String sessionId, String agentId, String userMessage);

    void endTrace(String traceId,
                  String status,
                  Integer totalSteps,
                  Long totalLatencyMs,
                  Integer totalPromptTokens,
                  Integer totalCompletionTokens,
                  String errorMessage);

    AgentStepTrace startStep(String traceId,
                             int stepIndex,
                             String phase,
                             String modelName);

    void endStep(String stepId,
                 String status,
                 Integer promptTokens,
                 Integer completionTokens,
                 Long latencyMs,
                 String errorMessage);

    ToolCallTrace startToolCall(String traceId,
                                String stepId,
                                String toolName,
                                String arguments);

    void endToolCall(String toolCallId,
                     String status,
                     String result,
                     Long latencyMs,
                     String errorMessage);

    AgentTrace getTraceById(String traceId);

    List<AgentTrace> getTracesBySessionId(String sessionId);

    List<AgentStepTrace> getStepsByTraceId(String traceId);

    List<ToolCallTrace> getToolCallsByTraceId(String traceId);
}
