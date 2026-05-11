package com.kama.jchatmind.model.response;

import com.kama.jchatmind.model.vo.McpServerVO;
import lombok.Builder;
import lombok.Data;

import java.util.List;

@Data
@Builder
public class GetMcpServersResponse {
    private List<McpServerVO> mcpServers;
}
