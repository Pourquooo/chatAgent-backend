package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.model.request.CreateMcpServerRequest;
import com.kama.jchatmind.model.request.UpdateMcpServerRequest;
import com.kama.jchatmind.model.response.CreateMcpServerResponse;
import com.kama.jchatmind.model.response.GetMcpServersResponse;
import com.kama.jchatmind.model.vo.McpToolPreviewVO;
import com.kama.jchatmind.service.McpServerFacadeService;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class McpServerController {

    private final McpServerFacadeService mcpServerFacadeService;

    @GetMapping("/mcp-servers")
    public ApiResponse<GetMcpServersResponse> getMcpServers() {
        return ApiResponse.success(mcpServerFacadeService.getMcpServers());
    }

    @PostMapping("/mcp-servers")
    public ApiResponse<CreateMcpServerResponse> createMcpServer(@RequestBody CreateMcpServerRequest request) {
        return ApiResponse.success(mcpServerFacadeService.createMcpServer(request));
    }

    @DeleteMapping("/mcp-servers/{mcpServerId}")
    public ApiResponse<Void> deleteMcpServer(@PathVariable String mcpServerId) {
        mcpServerFacadeService.deleteMcpServer(mcpServerId);
        return ApiResponse.success();
    }

    @PatchMapping("/mcp-servers/{mcpServerId}")
    public ApiResponse<Void> updateMcpServer(@PathVariable String mcpServerId,
                                             @RequestBody UpdateMcpServerRequest request) {
        mcpServerFacadeService.updateMcpServer(mcpServerId, request);
        return ApiResponse.success();
    }

    /** 拉取远端工具列表, 给 Agent 配置页预览用. */
    @GetMapping("/mcp-servers/{mcpServerId}/tools")
    public ApiResponse<List<McpToolPreviewVO>> previewTools(@PathVariable String mcpServerId) {
        return ApiResponse.success(mcpServerFacadeService.previewTools(mcpServerId));
    }

    /** 连通性检查, 不拉工具. */
    @PostMapping("/mcp-servers/{mcpServerId}/ping")
    public ApiResponse<Map<String, Object>> ping(@PathVariable String mcpServerId) {
        boolean ok = mcpServerFacadeService.ping(mcpServerId);
        return ApiResponse.success(Map.of("ok", ok));
    }
}
