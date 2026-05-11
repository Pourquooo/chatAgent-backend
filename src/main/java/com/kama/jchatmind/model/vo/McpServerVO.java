package com.kama.jchatmind.model.vo;

import com.kama.jchatmind.model.dto.McpServerDTO;
import lombok.Builder;
import lombok.Data;

/**
 * MCP 服务器对外视图 - authConfig 中敏感字段 (clientSecret, headers) 会被脱敏.
 */
@Data
@Builder
public class McpServerVO {
    private String id;

    private String name;

    private String description;

    private McpServerDTO.Transport transport;

    private String endpoint;

    private McpServerDTO.AuthType authType;

    /** 脱敏后的认证配置 (clientSecret 置空、headers 值替换为 ***) */
    private McpServerDTO.AuthConfig authConfig;

    private Boolean enabled;
}
