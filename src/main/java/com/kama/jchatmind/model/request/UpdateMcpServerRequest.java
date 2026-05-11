package com.kama.jchatmind.model.request;

import com.kama.jchatmind.model.dto.McpServerDTO;
import lombok.Data;

@Data
public class UpdateMcpServerRequest {
    private String name;
    private String description;
    private String transport;
    private String endpoint;
    private String authType;
    private McpServerDTO.AuthConfig authConfig;
    private Boolean enabled;
}
