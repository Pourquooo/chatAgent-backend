package com.kama.jchatmind.model.dto;

import com.fasterxml.jackson.annotation.JsonValue;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.Map;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class McpServerDTO {
    private String id;

    private String name;

    private String description;

    private Transport transport;

    private String endpoint;

    private AuthType authType;

    /** 结构随 AuthType 变化, 详见 003 迁移脚本注释 */
    private AuthConfig authConfig;

    private Boolean enabled;

    private LocalDateTime createdAt;

    private LocalDateTime updatedAt;

    @Getter
    @AllArgsConstructor
    public enum Transport {
        SSE("SSE"),
        STREAMABLE_HTTP("STREAMABLE_HTTP");

        @JsonValue
        private final String value;

        public static Transport fromValue(String v) {
            for (Transport t : values()) {
                if (t.value.equalsIgnoreCase(v)) return t;
            }
            throw new IllegalArgumentException("Unknown transport: " + v);
        }
    }

    @Getter
    @AllArgsConstructor
    public enum AuthType {
        NONE("NONE"),
        HEADER("HEADER"),
        OAUTH2("OAUTH2");

        @JsonValue
        private final String value;

        public static AuthType fromValue(String v) {
            for (AuthType t : values()) {
                if (t.value.equalsIgnoreCase(v)) return t;
            }
            throw new IllegalArgumentException("Unknown authType: " + v);
        }
    }

    /**
     * 统一的认证配置载体, 不同 authType 只用其中一部分字段.
     * 用扁平结构而非子类, 便于 Jackson 读写 jsonb.
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class AuthConfig {
        // HEADER
        private Map<String, String> headers;

        // OAUTH2 (Client Credentials)
        private String tokenUrl;
        private String clientId;
        private String clientSecret;
        private String scope;
        private String audience;
    }
}
