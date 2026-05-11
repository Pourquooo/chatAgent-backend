package com.kama.jchatmind.model.entity;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * @TableName mcp_server
 *
 * MCP 服务器元数据 - 运行时 McpClientRegistry 按此记录懒加载 McpSyncClient.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpServer {
    private String id;

    private String name;

    private String description;

    /** SSE / STREAMABLE_HTTP */
    private String transport;

    private String endpoint;

    /** NONE / HEADER / OAUTH2 */
    private String authType;

    /** JSON String (jsonb), 结构随 authType 变化, 详见 003 迁移脚本注释 */
    private String authConfig;

    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;
}
