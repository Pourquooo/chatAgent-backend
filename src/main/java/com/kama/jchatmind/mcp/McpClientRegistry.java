package com.kama.jchatmind.mcp;

import com.kama.jchatmind.model.dto.McpServerDTO;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpSyncClient;
import io.modelcontextprotocol.client.transport.WebClientStreamableHttpTransport;
import io.modelcontextprotocol.client.transport.WebFluxSseClientTransport;
import io.modelcontextprotocol.spec.McpClientTransport;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.annotation.PreDestroy;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.mcp.SyncMcpToolCallback;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.client.ExchangeFilterFunction;
import org.springframework.web.reactive.function.client.WebClient;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 按 serverId 懒加载并缓存 {@link McpSyncClient}.
 *
 * 关键设计:
 * 1. 配置变更 / 删除时通过 {@link #invalidate(String)} 断开旧连接;
 * 2. 任何 IO 失败都不抛出, 仅 warn 并返回空集合, 保持"远端异常不阻塞 Agent 主流程";
 * 3. 工具名前缀 {@code mcp_<serverId 前 8 位>_<原始工具名>} 避免和本地 Tool 撞名;
 * 4. OAuth2 Bearer 头通过 {@link ExchangeFilterFunction} 动态注入, 令牌失效时会自动刷新.
 */
@Slf4j
@Component
public class McpClientRegistry {

    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(30);
    private static final Duration INIT_TIMEOUT = Duration.ofSeconds(15);

    /** serverId -> 已初始化的 McpSyncClient. 配置变更即 invalidate. */
    private final ConcurrentHashMap<String, McpSyncClient> clients = new ConcurrentHashMap<>();

    private final OAuth2TokenService oauth2TokenService;

    public McpClientRegistry(OAuth2TokenService oauth2TokenService) {
        this.oauth2TokenService = oauth2TokenService;
    }

    /**
     * 构造工具名前缀. 取 serverId 前 8 位避免名称过长, 前端 trace / LLM 里容易定位.
     */
    public static String toolNamePrefix(String serverId) {
        if (!StringUtils.hasText(serverId)) return "mcp_unknown_";
        String head = serverId.replace("-", "");
        return "mcp_" + head.substring(0, Math.min(8, head.length())) + "_";
    }

    /** 原始 serverId -> VO 使用的脱敏前缀. 反向定位 source = MCP:<serverId>. */
    public static String sourceTag(String serverId) {
        return "MCP:" + serverId;
    }

    /**
     * 为某个 Agent 组装远端工具 Callback.
     * 调用链路: allowedMcps -> 对每个启用的 server 懒加载 client -> 列工具 -> 打包成 ToolCallback.
     */
    public List<McpToolBinding> resolveCallbacks(List<McpServerDTO> enabledServers) {
        List<McpToolBinding> bindings = new ArrayList<>();
        if (enabledServers == null || enabledServers.isEmpty()) return bindings;
        for (McpServerDTO server : enabledServers) {
            if (server == null || !Boolean.TRUE.equals(server.getEnabled())) continue;
            try {
                McpSyncClient client = getOrCreate(server);
                if (client == null) continue;
                McpSchema.ListToolsResult listResult = client.listTools();
                if (listResult == null || listResult.tools() == null) continue;
                String prefix = toolNamePrefix(server.getId());
                for (McpSchema.Tool tool : listResult.tools()) {
                    SyncMcpToolCallback cb = SyncMcpToolCallback.builder()
                            .mcpClient(client)
                            .tool(tool)
                            .prefixedToolName(prefix + tool.name())
                            .build();
                    bindings.add(new McpToolBinding(server.getId(), tool.name(), cb));
                }
            } catch (Exception e) {
                log.warn("[mcp] resolveCallbacks failed for server id={} name={}",
                        server.getId(), server.getName(), e);
                // 坏一个不影响其他, 继续
            }
        }
        return bindings;
    }

    /**
     * 列出某个 server 提供的工具 (不缓存 callback, 用于 UI 预览).
     */
    public List<McpSchema.Tool> listTools(McpServerDTO server) {
        try {
            McpSyncClient client = getOrCreate(server);
            if (client == null) return List.of();
            McpSchema.ListToolsResult r = client.listTools();
            return r == null || r.tools() == null ? List.of() : r.tools();
        } catch (Exception e) {
            log.warn("[mcp] listTools failed: id={}", server.getId(), e);
            throw new IllegalStateException("连接 MCP 服务器失败: " + e.getMessage(), e);
        }
    }

    /**
     * 主动健康检查. 成功返回 true.
     */
    public boolean ping(McpServerDTO server) {
        try {
            McpSyncClient client = getOrCreate(server);
            if (client == null) return false;
            client.ping();
            return true;
        } catch (Exception e) {
            log.warn("[mcp] ping failed: id={}", server.getId(), e);
            return false;
        }
    }

    /** 配置变更 / 禁用 / 删除时调用, 关闭旧连接. */
    public void invalidate(String serverId) {
        if (serverId == null) return;
        McpSyncClient removed = clients.remove(serverId);
        if (removed != null) {
            try {
                removed.closeGracefully();
            } catch (Exception e) {
                log.warn("[mcp] close failed, id={}", serverId, e);
            }
        }
        oauth2TokenService.invalidate(serverId);
    }

    @PreDestroy
    public void shutdown() {
        for (Map.Entry<String, McpSyncClient> e : clients.entrySet()) {
            try {
                e.getValue().closeGracefully();
            } catch (Exception ex) {
                log.warn("[mcp] shutdown close failed, id={}", e.getKey(), ex);
            }
        }
        clients.clear();
    }

    private McpSyncClient getOrCreate(McpServerDTO server) {
        if (server == null || server.getId() == null) return null;
        return clients.computeIfAbsent(server.getId(), id -> buildClient(server));
    }

    private McpSyncClient buildClient(McpServerDTO server) {
        McpClientTransport transport = buildTransport(server);
        McpSyncClient client = McpClient.sync(transport)
                .requestTimeout(REQUEST_TIMEOUT)
                .initializationTimeout(INIT_TIMEOUT)
                .clientInfo(new McpSchema.Implementation("jchatmind", "1.0.0"))
                .build();
        client.initialize();
        log.info("[mcp] initialized client: id={} name={} transport={} endpoint={}",
                server.getId(), server.getName(), server.getTransport(), server.getEndpoint());
        return client;
    }

    private McpClientTransport buildTransport(McpServerDTO server) {
        UriParts parts = splitEndpoint(server.getEndpoint());
        WebClient.Builder builder = WebClient.builder().baseUrl(parts.baseUrl);

        // 认证: 静态 headers / OAuth2 Bearer
        applyAuth(builder, server);

        McpServerDTO.Transport t = server.getTransport() == null
                ? McpServerDTO.Transport.SSE
                : server.getTransport();
        return switch (t) {
            case SSE -> WebFluxSseClientTransport.builder(builder)
                    .sseEndpoint(parts.pathAndQuery)
                    .build();
            case STREAMABLE_HTTP -> WebClientStreamableHttpTransport.builder(builder)
                    .endpoint(parts.pathAndQuery)
                    .build();
        };
    }

    private void applyAuth(WebClient.Builder builder, McpServerDTO server) {
        if (server.getAuthType() == null || server.getAuthType() == McpServerDTO.AuthType.NONE) {
            return;
        }
        McpServerDTO.AuthConfig auth = server.getAuthConfig();
        if (auth == null) return;

        switch (server.getAuthType()) {
            case HEADER -> {
                if (auth.getHeaders() != null) {
                    for (Map.Entry<String, String> h : auth.getHeaders().entrySet()) {
                        if (h.getKey() == null || h.getValue() == null) continue;
                        builder.defaultHeader(h.getKey(), h.getValue());
                    }
                }
            }
            case OAUTH2 -> {
                // 动态 Bearer 注入: 每次发请求前从 OAuth2TokenService 拿 token, 自动处理过期刷新.
                builder.filter(oauth2BearerFilter(server.getId(), auth));
            }
            default -> { /* NONE 已提前返回 */ }
        }
    }

    private ExchangeFilterFunction oauth2BearerFilter(String serverId, McpServerDTO.AuthConfig auth) {
        return (request, next) -> {
            try {
                String token = oauth2TokenService.getAccessToken(serverId, auth);
                return next.exchange(
                        org.springframework.web.reactive.function.client.ClientRequest
                                .from(request)
                                .headers(h -> h.setBearerAuth(token))
                                .build()
                );
            } catch (Exception e) {
                log.warn("[mcp.oauth2] inject bearer failed, serverId={}: {}", serverId, e.getMessage());
                // 透传原请求, 服务器将拒绝并由上层记录失败
                return next.exchange(request);
            }
        };
    }

    private UriParts splitEndpoint(String endpoint) {
        if (!StringUtils.hasText(endpoint)) {
            throw new IllegalArgumentException("endpoint 不能为空");
        }
        URI uri = URI.create(endpoint.trim());
        if (uri.getScheme() == null || uri.getHost() == null) {
            throw new IllegalArgumentException("endpoint 必须是完整 URL, 例如 https://host:port/path");
        }
        StringBuilder base = new StringBuilder();
        base.append(uri.getScheme()).append("://").append(uri.getHost());
        if (uri.getPort() > 0) base.append(":").append(uri.getPort());
        String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
        if (uri.getRawQuery() != null) path = path + "?" + uri.getRawQuery();
        return new UriParts(base.toString(), path);
    }

    private record UriParts(String baseUrl, String pathAndQuery) {}

    /** resolveCallbacks 的返回, 让上层知道每个 callback 对应的 serverId 和原始工具名, 便于 trace 打 source. */
    public record McpToolBinding(String serverId, String originalToolName, ToolCallback callback) {}
}
