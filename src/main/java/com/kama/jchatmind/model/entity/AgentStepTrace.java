package com.kama.jchatmind.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * @TableName agent_step_trace
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentStepTrace {
    private String id;

    private String traceId;

    private Integer stepIndex;

    /** THINK / EXECUTE */
    private String phase;

    private String modelName;

    private Integer promptTokens;

    private Integer completionTokens;

    private Long latencyMs;

    /** RUNNING / SUCCESS / ERROR */
    private String status;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;
}
