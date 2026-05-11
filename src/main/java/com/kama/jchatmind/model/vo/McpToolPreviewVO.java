package com.kama.jchatmind.model.vo;

import lombok.Builder;
import lombok.Data;

/**
 * MCP 远端工具预览 - UI 配置页调用 /api/mcp-servers/{id}/tools 拿到, 让用户确认连通性和能力集.
 * 直接用 McpSchema.Tool 也可以, 但那是 SDK 内部类, 维护风险高, 包一层可控字段.
 */
@Data
@Builder
public class McpToolPreviewVO {
    private String name;
    private String description;
    /** JSON Schema 字符串, 前端展示时可选折叠. */
    private String inputSchema;
}
