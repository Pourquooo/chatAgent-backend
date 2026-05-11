package com.kama.jchatmind.mcp;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.dto.McpServerDTO;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.MultiValueMap;
import org.springframework.util.StringUtils;
import org.springframework.web.reactive.function.BodyInserters;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.client.WebClientResponseException;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * OAuth2 Client Credentials 令牌服务.
 * <p>
 * 负责:
 * 1. 按 serverId 缓存 access_token 及到期时间, 未到期复用;
 * 2. 到期前 60s 主动刷新;
 * 3. 获取失败只抛一次 RuntimeException, 由上层 registry 捕获后降级.
 *
 * 只实现 RFC 6749 §4.4 (client credentials) - 目前 MCP 生态里机器间认证最常见的形态.
 * 其他 flow (authorization_code / device_code) 等用户发声再扩展.
 */
@Slf4j
@Component
public class OAuth2TokenService {

    /** 过期前 60 秒视为需要刷新, 避免边界条件下请求用到过期 token. */
    private static final long REFRESH_SKEW_SECONDS = 60L;

    /** 无 expires_in 时的兜底缓存时长, 避免永久缓存. */
    private static final long DEFAULT_TTL_SECONDS = 300L;

    private final ConcurrentHashMap<String, CachedToken> cache = new ConcurrentHashMap<>();
    private final WebClient webClient = WebClient.builder().build();
    private final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * 获取 serverId 对应的 access_token, 必要时同步刷新.
     * serverId 仅用作缓存 key; 实际请求参数来自 auth (tokenUrl/clientId/clientSecret/scope/audience).
     */
    public String getAccessToken(String serverId, McpServerDTO.AuthConfig auth) {
        Assert.notNull(auth, "auth config cannot be null");
        Assert.hasText(auth.getTokenUrl(), "OAuth2 tokenUrl is required");
        Assert.hasText(auth.getClientId(), "OAuth2 clientId is required");
        Assert.hasText(auth.getClientSecret(), "OAuth2 clientSecret is required");

        String cacheKey = serverId + "|" + auth.getTokenUrl() + "|" + auth.getClientId();
        CachedToken existing = cache.get(cacheKey);
        if (existing != null && !existing.isExpiringSoon()) {
            return existing.accessToken;
        }

        CachedToken fresh = requestToken(auth);
        cache.put(cacheKey, fresh);
        return fresh.accessToken;
    }

    /** 主动失效 (更新配置 / 删除服务器时调用). */
    public void invalidate(String serverId) {
        if (serverId == null) return;
        cache.keySet().removeIf(k -> k.startsWith(serverId + "|"));
    }

    private CachedToken requestToken(McpServerDTO.AuthConfig auth) {
        try {
            MultiValueMap<String, String> form = new org.springframework.util.LinkedMultiValueMap<>();
            form.add("grant_type", "client_credentials");
            form.add("client_id", auth.getClientId());
            form.add("client_secret", auth.getClientSecret());
            if (StringUtils.hasText(auth.getScope())) {
                form.add("scope", auth.getScope());
            }
            if (StringUtils.hasText(auth.getAudience())) {
                form.add("audience", auth.getAudience());
            }

            String body = webClient.post()
                    .uri(auth.getTokenUrl())
                    .header("Content-Type", "application/x-www-form-urlencoded")
                    .header("Accept", "application/json")
                    .body(BodyInserters.fromFormData(form))
                    .retrieve()
                    .bodyToMono(String.class)
                    .block(Duration.ofSeconds(10));

            if (!StringUtils.hasText(body)) {
                throw new IllegalStateException("Empty token response");
            }
            TokenResponse resp = objectMapper.readValue(body, TokenResponse.class);
            if (!StringUtils.hasText(resp.accessToken)) {
                throw new IllegalStateException("Token response missing access_token: " + body);
            }
            long ttl = resp.expiresIn != null && resp.expiresIn > 0 ? resp.expiresIn : DEFAULT_TTL_SECONDS;
            return new CachedToken(resp.accessToken, Instant.now().plusSeconds(ttl));
        } catch (WebClientResponseException e) {
            log.warn("[mcp.oauth2] token endpoint returned {} {}: {}",
                    e.getStatusCode(), e.getStatusText(), e.getResponseBodyAsString());
            throw new IllegalStateException("OAuth2 token request failed: " + e.getStatusCode(), e);
        } catch (Exception e) {
            throw new IllegalStateException("OAuth2 token request failed", e);
        }
    }

    private record CachedToken(String accessToken, Instant expiresAt) {
        boolean isExpiringSoon() {
            return Instant.now().plusSeconds(REFRESH_SKEW_SECONDS).isAfter(expiresAt);
        }
    }

    @JsonIgnoreProperties(ignoreUnknown = true)
    private static class TokenResponse {
        @JsonProperty("access_token")
        String accessToken;
        @JsonProperty("token_type")
        String tokenType;
        @JsonProperty("expires_in")
        Long expiresIn;
        @JsonProperty("scope")
        String scope;
    }

    /** 仅测试/诊断用: 不经缓存直接请求一次. */
    public Map<String, Object> debugFetch(McpServerDTO.AuthConfig auth) throws Exception {
        CachedToken t = requestToken(auth);
        return objectMapper.readValue(
                objectMapper.writeValueAsString(t),
                new TypeReference<>() {}
        );
    }
}
