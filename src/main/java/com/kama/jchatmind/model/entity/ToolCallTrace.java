package com.kama.jchatmind.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * @TableName tool_call_trace
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ToolCallTrace {
    private String id;

    private String traceId;

    private String stepId;

    private String toolName;

    /** JSON String (jsonb) */
    private String arguments;

    private String result;

    private Long latencyMs;

    /** RUNNING / SUCCESS / ERROR */
    private String status;

    private String errorMessage;

    /** LOCAL / MCP:&lt;serverId&gt;; 旧数据为 NULL, 前端按 LOCAL 显示 */
    private String source;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;
}
