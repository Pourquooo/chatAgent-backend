package com.kama.jchatmind.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.dto.McpServerDTO;
import com.kama.jchatmind.model.entity.McpServer;
import com.kama.jchatmind.model.request.CreateMcpServerRequest;
import com.kama.jchatmind.model.request.UpdateMcpServerRequest;
import com.kama.jchatmind.model.vo.McpServerVO;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.HashMap;
import java.util.Map;

@Component
@AllArgsConstructor
public class McpServerConverter {

    private static final String MASK = "******";

    private final ObjectMapper objectMapper;

    public McpServer toEntity(McpServerDTO dto) throws JsonProcessingException {
        Assert.notNull(dto, "McpServerDTO cannot be null");
        Assert.notNull(dto.getTransport(), "transport cannot be null");
        Assert.notNull(dto.getAuthType(), "authType cannot be null");
        Assert.hasText(dto.getEndpoint(), "endpoint cannot be blank");

        McpServerDTO.AuthConfig authConfig = dto.getAuthConfig() == null
                ? McpServerDTO.AuthConfig.builder().build()
                : dto.getAuthConfig();

        return McpServer.builder()
                .id(dto.getId())
                .name(dto.getName())
                .description(dto.getDescription())
                .transport(dto.getTransport().getValue())
                .endpoint(dto.getEndpoint())
                .authType(dto.getAuthType().getValue())
                .authConfig(objectMapper.writeValueAsString(authConfig))
                .enabled(dto.getEnabled() == null ? Boolean.TRUE : dto.getEnabled())
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .build();
    }

    public McpServerDTO toDTO(McpServer entity) throws JsonProcessingException {
        Assert.notNull(entity, "McpServer entity cannot be null");

        McpServerDTO.AuthConfig authConfig = StringUtils.hasText(entity.getAuthConfig())
                ? objectMapper.readValue(entity.getAuthConfig(), McpServerDTO.AuthConfig.class)
                : McpServerDTO.AuthConfig.builder().build();

        return McpServerDTO.builder()
                .id(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .transport(McpServerDTO.Transport.fromValue(entity.getTransport()))
                .endpoint(entity.getEndpoint())
                .authType(McpServerDTO.AuthType.fromValue(entity.getAuthType()))
                .authConfig(authConfig)
                .enabled(Boolean.TRUE.equals(entity.getEnabled()))
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public McpServerVO toVO(McpServerDTO dto) {
        return McpServerVO.builder()
                .id(dto.getId())
                .name(dto.getName())
                .description(dto.getDescription())
                .transport(dto.getTransport())
                .endpoint(dto.getEndpoint())
                .authType(dto.getAuthType())
                .authConfig(sanitize(dto.getAuthConfig()))
                .enabled(Boolean.TRUE.equals(dto.getEnabled()))
                .build();
    }

    public McpServerVO toVO(McpServer entity) throws JsonProcessingException {
        return toVO(toDTO(entity));
    }

    public McpServerDTO toDTO(CreateMcpServerRequest request) {
        Assert.notNull(request, "CreateMcpServerRequest cannot be null");
        Assert.hasText(request.getEndpoint(), "endpoint cannot be blank");
        Assert.hasText(request.getTransport(), "transport cannot be blank");
        String authType = StringUtils.hasText(request.getAuthType()) ? request.getAuthType() : "NONE";
        return McpServerDTO.builder()
                .name(request.getName())
                .description(request.getDescription())
                .transport(McpServerDTO.Transport.fromValue(request.getTransport()))
                .endpoint(request.getEndpoint())
                .authType(McpServerDTO.AuthType.fromValue(authType))
                .authConfig(request.getAuthConfig() == null ? McpServerDTO.AuthConfig.builder().build() : request.getAuthConfig())
                .enabled(request.getEnabled() == null ? Boolean.TRUE : request.getEnabled())
                .build();
    }

    public void applyUpdate(McpServerDTO dto, UpdateMcpServerRequest req) {
        Assert.notNull(dto, "dto cannot be null");
        Assert.notNull(req, "request cannot be null");
        if (req.getName() != null) dto.setName(req.getName());
        if (req.getDescription() != null) dto.setDescription(req.getDescription());
        if (req.getTransport() != null) dto.setTransport(McpServerDTO.Transport.fromValue(req.getTransport()));
        if (req.getEndpoint() != null) dto.setEndpoint(req.getEndpoint());
        if (req.getAuthType() != null) dto.setAuthType(McpServerDTO.AuthType.fromValue(req.getAuthType()));
        if (req.getAuthConfig() != null) {
            // 增量合并: clientSecret/headers value 为空时保留旧值, 避免 UI 回显脱敏值造成覆盖
            dto.setAuthConfig(mergeAuthConfig(dto.getAuthConfig(), req.getAuthConfig()));
        }
        if (req.getEnabled() != null) dto.setEnabled(req.getEnabled());
    }

    private McpServerDTO.AuthConfig sanitize(McpServerDTO.AuthConfig in) {
        if (in == null) return null;
        Map<String, String> maskedHeaders = null;
        if (in.getHeaders() != null && !in.getHeaders().isEmpty()) {
            maskedHeaders = new HashMap<>();
            for (Map.Entry<String, String> e : in.getHeaders().entrySet()) {
                maskedHeaders.put(e.getKey(), MASK);
            }
        }
        return McpServerDTO.AuthConfig.builder()
                .headers(maskedHeaders)
                .tokenUrl(in.getTokenUrl())
                .clientId(in.getClientId())
                .clientSecret(StringUtils.hasText(in.getClientSecret()) ? MASK : null)
                .scope(in.getScope())
                .audience(in.getAudience())
                .build();
    }

    private McpServerDTO.AuthConfig mergeAuthConfig(McpServerDTO.AuthConfig old, McpServerDTO.AuthConfig incoming) {
        McpServerDTO.AuthConfig base = old == null ? McpServerDTO.AuthConfig.builder().build() : old;
        McpServerDTO.AuthConfig.AuthConfigBuilder b = McpServerDTO.AuthConfig.builder()
                .headers(incoming.getHeaders() != null ? mergeHeaders(base.getHeaders(), incoming.getHeaders()) : base.getHeaders())
                .tokenUrl(incoming.getTokenUrl() != null ? incoming.getTokenUrl() : base.getTokenUrl())
                .clientId(incoming.getClientId() != null ? incoming.getClientId() : base.getClientId())
                .scope(incoming.getScope() != null ? incoming.getScope() : base.getScope())
                .audience(incoming.getAudience() != null ? incoming.getAudience() : base.getAudience());
        // clientSecret: 前端若回传 MASK 或空字符串, 认为"不修改"
        String incomingSecret = incoming.getClientSecret();
        if (incomingSecret == null || MASK.equals(incomingSecret) || incomingSecret.isBlank()) {
            b.clientSecret(base.getClientSecret());
        } else {
            b.clientSecret(incomingSecret);
        }
        return b.build();
    }

    private Map<String, String> mergeHeaders(Map<String, String> old, Map<String, String> incoming) {
        Map<String, String> merged = new HashMap<>();
        if (old != null) merged.putAll(old);
        for (Map.Entry<String, String> e : incoming.entrySet()) {
            String v = e.getValue();
            if (v == null || MASK.equals(v)) {
                // 保留旧值
                continue;
            }
            merged.put(e.getKey(), v);
        }
        // incoming 没覆盖的 key 保留旧值, incoming 移除功能未提供 (避免脱敏回显误删)
        return merged;
    }
}
