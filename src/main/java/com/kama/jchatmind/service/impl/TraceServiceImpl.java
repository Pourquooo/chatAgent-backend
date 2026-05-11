package com.kama.jchatmind.service.impl;

import com.kama.jchatmind.mapper.AgentStepTraceMapper;
import com.kama.jchatmind.mapper.AgentTraceMapper;
import com.kama.jchatmind.mapper.ToolCallTraceMapper;
import com.kama.jchatmind.model.entity.AgentStepTrace;
import com.kama.jchatmind.model.entity.AgentTrace;
import com.kama.jchatmind.model.entity.ToolCallTrace;
import com.kama.jchatmind.service.TraceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.Collections;
import java.util.List;

@Slf4j
@Service
public class TraceServiceImpl implements TraceService {

    private static final int USER_MESSAGE_MAX_LEN = 2000;

    private final AgentTraceMapper agentTraceMapper;
    private final AgentStepTraceMapper agentStepTraceMapper;
    private final ToolCallTraceMapper toolCallTraceMapper;

    public TraceServiceImpl(AgentTraceMapper agentTraceMapper,
                            AgentStepTraceMapper agentStepTraceMapper,
                            ToolCallTraceMapper toolCallTraceMapper) {
        this.agentTraceMapper = agentTraceMapper;
        this.agentStepTraceMapper = agentStepTraceMapper;
        this.toolCallTraceMapper = toolCallTraceMapper;
    }

    @Override
    public AgentTrace startTrace(String sessionId, String agentId, String userMessage) {
        try {
            AgentTrace trace = AgentTrace.builder()
                    .sessionId(sessionId)
                    .agentId(agentId)
                    .userMessage(truncate(userMessage))
                    .status(STATUS_RUNNING)
                    .startedAt(LocalDateTime.now())
                    .build();
            agentTraceMapper.insert(trace);
            return trace;
        } catch (Exception e) {
            log.warn("[trace] startTrace failed, sessionId={}, agentId={}", sessionId, agentId, e);
            return null;
        }
    }

    @Override
    public void endTrace(String traceId,
                         String status,
                         Integer totalSteps,
                         Long totalLatencyMs,
                         Integer totalPromptTokens,
                         Integer totalCompletionTokens,
                         String errorMessage) {
        if (traceId == null) return;
        try {
            AgentTrace update = AgentTrace.builder()
                    .id(traceId)
                    .status(status)
                    .totalSteps(totalSteps)
                    .totalLatencyMs(totalLatencyMs)
                    .totalPromptTokens(totalPromptTokens)
                    .totalCompletionTokens(totalCompletionTokens)
                    .errorMessage(errorMessage)
                    .finishedAt(LocalDateTime.now())
                    .build();
            agentTraceMapper.updateById(update);
        } catch (Exception e) {
            log.warn("[trace] endTrace failed, traceId={}", traceId, e);
        }
    }

    @Override
    public AgentStepTrace startStep(String traceId, int stepIndex, String phase, String modelName) {
        if (traceId == null) return null;
        try {
            AgentStepTrace step = AgentStepTrace.builder()
                    .traceId(traceId)
                    .stepIndex(stepIndex)
                    .phase(phase)
                    .modelName(modelName)
                    .status(STATUS_RUNNING)
                    .startedAt(LocalDateTime.now())
                    .build();
            agentStepTraceMapper.insert(step);
            return step;
        } catch (Exception e) {
            log.warn("[trace] startStep failed, traceId={}, stepIndex={}", traceId, stepIndex, e);
            return null;
        }
    }

    @Override
    public void endStep(String stepId,
                        String status,
                        Integer promptTokens,
                        Integer completionTokens,
                        Long latencyMs,
                        String errorMessage) {
        if (stepId == null) return;
        try {
            AgentStepTrace update = AgentStepTrace.builder()
                    .id(stepId)
                    .status(status)
                    .promptTokens(promptTokens)
                    .completionTokens(completionTokens)
                    .latencyMs(latencyMs)
                    .errorMessage(errorMessage)
                    .finishedAt(LocalDateTime.now())
                    .build();
            agentStepTraceMapper.updateById(update);
        } catch (Exception e) {
            log.warn("[trace] endStep failed, stepId={}", stepId, e);
        }
    }

    @Override
    public ToolCallTrace startToolCall(String traceId, String stepId, String toolName, String arguments, String source) {
        if (traceId == null || stepId == null) return null;
        try {
            ToolCallTrace tc = ToolCallTrace.builder()
                    .traceId(traceId)
                    .stepId(stepId)
                    .toolName(toolName)
                    .arguments(normalizeJson(arguments))
                    .source(source == null ? SOURCE_LOCAL : source)
                    .status(STATUS_RUNNING)
                    .startedAt(LocalDateTime.now())
                    .build();
            toolCallTraceMapper.insert(tc);
            return tc;
        } catch (Exception e) {
            log.warn("[trace] startToolCall failed, traceId={}, tool={}", traceId, toolName, e);
            return null;
        }
    }

    @Override
    public void endToolCall(String toolCallId,
                            String status,
                            String result,
                            Long latencyMs,
                            String errorMessage) {
        if (toolCallId == null) return;
        try {
            ToolCallTrace update = ToolCallTrace.builder()
                    .id(toolCallId)
                    .status(status)
                    .result(result)
                    .latencyMs(latencyMs)
                    .errorMessage(errorMessage)
                    .finishedAt(LocalDateTime.now())
                    .build();
            toolCallTraceMapper.updateById(update);
        } catch (Exception e) {
            log.warn("[trace] endToolCall failed, toolCallId={}", toolCallId, e);
        }
    }

    @Override
    public AgentTrace getTraceById(String traceId) {
        return agentTraceMapper.selectById(traceId);
    }

    @Override
    public List<AgentTrace> getTracesBySessionId(String sessionId) {
        List<AgentTrace> list = agentTraceMapper.selectBySessionId(sessionId);
        return list == null ? Collections.emptyList() : list;
    }

    @Override
    public List<AgentStepTrace> getStepsByTraceId(String traceId) {
        List<AgentStepTrace> list = agentStepTraceMapper.selectByTraceId(traceId);
        return list == null ? Collections.emptyList() : list;
    }

    @Override
    public List<ToolCallTrace> getToolCallsByTraceId(String traceId) {
        List<ToolCallTrace> list = toolCallTraceMapper.selectByTraceId(traceId);
        return list == null ? Collections.emptyList() : list;
    }

    private String truncate(String s) {
        if (s == null) return null;
        return s.length() <= USER_MESSAGE_MAX_LEN ? s : s.substring(0, USER_MESSAGE_MAX_LEN);
    }

    // jsonb 列要求合法 JSON；工具参数理论上已经是 JSON 字符串，这里做兜底：
    // null / 空串 → null（DB 写入 NULL）；非 JSON 形态 → 包一层字符串。
    private String normalizeJson(String s) {
        if (s == null || s.isBlank()) return null;
        String trimmed = s.trim();
        char c = trimmed.charAt(0);
        if (c == '{' || c == '[' || c == '"') return trimmed;
        return "\"" + trimmed.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
