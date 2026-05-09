package com.kama.jchatmind.model.response;

import com.kama.jchatmind.model.entity.AgentStepTrace;
import com.kama.jchatmind.model.entity.AgentTrace;
import com.kama.jchatmind.model.entity.ToolCallTrace;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Trace 详情响应：一次 Agent 运行的完整链路（头 + 所有步骤 + 所有工具调用）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class GetTraceDetailResponse {

    private AgentTrace trace;

    private List<AgentStepTrace> steps;

    private List<ToolCallTrace> toolCalls;
}
