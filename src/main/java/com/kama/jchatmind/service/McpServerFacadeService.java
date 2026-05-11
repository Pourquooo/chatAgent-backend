package com.kama.jchatmind.service;

import com.kama.jchatmind.model.request.CreateMcpServerRequest;
import com.kama.jchatmind.model.request.UpdateMcpServerRequest;
import com.kama.jchatmind.model.response.CreateMcpServerResponse;
import com.kama.jchatmind.model.response.GetMcpServersResponse;
import com.kama.jchatmind.model.vo.McpToolPreviewVO;

import java.util.List;

/**
 * MCP 服务器配置 CRUD + 诊断 (ping / tools 预览) 的外观服务.
 *
 * 写入路径 (update / delete / enabled 切换) 必须调用 McpClientRegistry.invalidate,
 * 以保证老连接不再被复用.
 */
public interface McpServerFacadeService {

    GetMcpServersResponse getMcpServers();

    CreateMcpServerResponse createMcpServer(CreateMcpServerRequest request);

    void deleteMcpServer(String mcpServerId);

    void updateMcpServer(String mcpServerId, UpdateMcpServerRequest request);

    /** 用当前配置懒连一次, 拉取远端工具清单. 失败抛 BizException. */
    List<McpToolPreviewVO> previewTools(String mcpServerId);

    /** 纯连通检查, 不读工具列表. */
    boolean ping(String mcpServerId);
}
