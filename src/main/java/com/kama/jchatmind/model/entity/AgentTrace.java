package com.kama.jchatmind.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * @TableName agent_trace
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class AgentTrace {
    private String id;

    private String sessionId;

    private String agentId;

    private String userMessage;

    /** RUNNING / FINISHED / ERROR */
    private String status;

    private Integer totalSteps;

    private Long totalLatencyMs;

    private Integer totalPromptTokens;

    private Integer totalCompletionTokens;

    private String errorMessage;

    private LocalDateTime startedAt;

    private LocalDateTime finishedAt;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
