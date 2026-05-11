package com.kama.jchatmind.service.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.converter.McpServerConverter;
import com.kama.jchatmind.exception.BizException;
import com.kama.jchatmind.mapper.McpServerMapper;
import com.kama.jchatmind.mcp.McpClientRegistry;
import com.kama.jchatmind.model.dto.McpServerDTO;
import com.kama.jchatmind.model.entity.McpServer;
import com.kama.jchatmind.model.request.CreateMcpServerRequest;
import com.kama.jchatmind.model.request.UpdateMcpServerRequest;
import com.kama.jchatmind.model.response.CreateMcpServerResponse;
import com.kama.jchatmind.model.response.GetMcpServersResponse;
import com.kama.jchatmind.model.vo.McpServerVO;
import com.kama.jchatmind.model.vo.McpToolPreviewVO;
import com.kama.jchatmind.service.McpServerFacadeService;
import io.modelcontextprotocol.spec.McpSchema;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Slf4j
@Service
public class McpServerFacadeServiceImpl implements McpServerFacadeService {

    private final McpServerMapper mcpServerMapper;
    private final McpServerConverter mcpServerConverter;
    private final McpClientRegistry mcpClientRegistry;
    private final ObjectMapper objectMapper;

    public McpServerFacadeServiceImpl(McpServerMapper mcpServerMapper,
                                      McpServerConverter mcpServerConverter,
                                      McpClientRegistry mcpClientRegistry,
                                      ObjectMapper objectMapper) {
        this.mcpServerMapper = mcpServerMapper;
        this.mcpServerConverter = mcpServerConverter;
        this.mcpClientRegistry = mcpClientRegistry;
        this.objectMapper = objectMapper;
    }

    @Override
    public GetMcpServersResponse getMcpServers() {
        List<McpServer> rows = mcpServerMapper.selectAll();
        List<McpServerVO> list = new ArrayList<>();
        for (McpServer row : rows) {
            try {
                list.add(mcpServerConverter.toVO(row));
            } catch (JsonProcessingException e) {
                log.warn("[mcp] decode authConfig failed, id={}", row.getId(), e);
                // 坏一条不影响整体列表
            }
        }
        return GetMcpServersResponse.builder().mcpServers(list).build();
    }

    @Override
    public CreateMcpServerResponse createMcpServer(CreateMcpServerRequest request) {
        try {
            McpServerDTO dto = mcpServerConverter.toDTO(request);
            McpServer entity = mcpServerConverter.toEntity(dto);
            LocalDateTime now = LocalDateTime.now();
            entity.setCreatedAt(now);
            entity.setUpdatedAt(now);
            int r = mcpServerMapper.insert(entity);
            if (r <= 0) throw new BizException("创建 MCP 服务器失败");
            return CreateMcpServerResponse.builder().mcpServerId(entity.getId()).build();
        } catch (JsonProcessingException e) {
            throw new BizException("创建 MCP 服务器时序列化失败: " + e.getMessage());
        }
    }

    @Override
    public void deleteMcpServer(String mcpServerId) {
        McpServer existing = mcpServerMapper.selectById(mcpServerId);
        if (existing == null) throw new BizException("MCP 服务器不存在: " + mcpServerId);
        int r = mcpServerMapper.deleteById(mcpServerId);
        if (r <= 0) throw new BizException("删除 MCP 服务器失败");
        mcpClientRegistry.invalidate(mcpServerId);
    }

    @Override
    public void updateMcpServer(String mcpServerId, UpdateMcpServerRequest request) {
        try {
            McpServer existing = mcpServerMapper.selectById(mcpServerId);
            if (existing == null) throw new BizException("MCP 服务器不存在: " + mcpServerId);

            McpServerDTO dto = mcpServerConverter.toDTO(existing);
            mcpServerConverter.applyUpdate(dto, request);

            McpServer updated = mcpServerConverter.toEntity(dto);
            updated.setId(existing.getId());
            updated.setCreatedAt(existing.getCreatedAt());
            updated.setUpdatedAt(LocalDateTime.now());

            int r = mcpServerMapper.updateById(updated);
            if (r <= 0) throw new BizException("更新 MCP 服务器失败");

            // 任何字段变更都可能影响连接 (endpoint / auth / transport), 一律失效缓存.
            mcpClientRegistry.invalidate(mcpServerId);
        } catch (JsonProcessingException e) {
            throw new BizException("更新 MCP 服务器时序列化失败: " + e.getMessage());
        }
    }

    @Override
    public List<McpToolPreviewVO> previewTools(String mcpServerId) {
        McpServerDTO dto = loadEnabledDto(mcpServerId);
        List<McpSchema.Tool> tools;
        try {
            tools = mcpClientRegistry.listTools(dto);
        } catch (Exception e) {
            throw new BizException("连接 MCP 服务器失败: " + e.getMessage());
        }
        List<McpToolPreviewVO> list = new ArrayList<>();
        for (McpSchema.Tool t : tools) {
            String schema = null;
            try {
                if (t.inputSchema() != null) {
                    schema = objectMapper.writeValueAsString(t.inputSchema());
                }
            } catch (JsonProcessingException ignore) {
                // 预览用, 不可序列化就置空
            }
            list.add(McpToolPreviewVO.builder()
                    .name(t.name())
                    .description(t.description())
                    .inputSchema(schema)
                    .build());
        }
        return list;
    }

    @Override
    public boolean ping(String mcpServerId) {
        McpServerDTO dto = loadEnabledDto(mcpServerId);
        return mcpClientRegistry.ping(dto);
    }

    private McpServerDTO loadEnabledDto(String mcpServerId) {
        McpServer existing = mcpServerMapper.selectById(mcpServerId);
        if (existing == null) throw new BizException("MCP 服务器不存在: " + mcpServerId);
        if (!Boolean.TRUE.equals(existing.getEnabled())) {
            throw new BizException("MCP 服务器已禁用, 请先启用再测试");
        }
        try {
            return mcpServerConverter.toDTO(existing);
        } catch (JsonProcessingException e) {
            throw new BizException("MCP 服务器配置解析失败: " + e.getMessage());
        }
    }
}
