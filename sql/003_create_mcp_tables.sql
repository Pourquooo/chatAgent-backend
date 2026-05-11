-- =====================================================================
-- Phase 3: MCP 支持
--
-- 1. mcp_server        : 独立管理的 MCP 服务器配置 (CRUD 由用户在 UI 维护)
-- 2. agent.allowed_mcps: Agent 勾选的 mcp_server id 列表, 语义与 allowed_tools
--                        / allowed_skills 并列 (JSONB 数组)
-- 3. tool_call_trace.source: 区分本次工具调用来源 (LOCAL / MCP:<serverId>)
--
-- 设计与现有风格对齐:
--   - 所有 list/对象字段用 JSONB, 读取 CAST(... AS text), 写入 CAST(? AS jsonb)
--   - 主键 UUID, gen_random_uuid() 生成 (Phase 1 已启用 pgcrypto)
--   - transport / auth_type 用 VARCHAR 不用 enum, 对齐 chat_message.role 风格
-- =====================================================================

-- ---------------------------------------------------------------------
-- 1. mcp_server: MCP 服务器元数据
-- ---------------------------------------------------------------------
-- transport : SSE | STREAMABLE_HTTP
-- auth_type : NONE | HEADER | OAUTH2
--   - NONE     : 无认证
--   - HEADER   : 静态请求头, 直接把 auth_config.headers 合并进每个请求
--   - OAUTH2   : Client Credentials 模式, auth_config 存 token 端点/client_id/
--                client_secret/scope; 运行时后端换 access_token 并以 Bearer 头注入
-- auth_config: JSONB, 结构随 auth_type 而变
--   HEADER  => {"headers": {"X-API-Key": "...", ...}}
--   OAUTH2  => {"tokenUrl":"...", "clientId":"...", "clientSecret":"...",
--               "scope":"optional", "audience":"optional"}
-- enabled   : 关闭时 McpClientRegistry 直接跳过, 配合"软禁用"
-- ---------------------------------------------------------------------
CREATE TABLE IF NOT EXISTS mcp_server (
    id           UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    name         VARCHAR(128) NOT NULL,
    description  TEXT,
    transport    VARCHAR(32)  NOT NULL DEFAULT 'SSE',
    endpoint     TEXT         NOT NULL,
    auth_type    VARCHAR(32)  NOT NULL DEFAULT 'NONE',
    auth_config  JSONB        NOT NULL DEFAULT '{}'::jsonb,
    enabled      BOOLEAN      NOT NULL DEFAULT TRUE,
    created_at   TIMESTAMP    NOT NULL DEFAULT NOW(),
    updated_at   TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_mcp_server_enabled     ON mcp_server (enabled);
CREATE INDEX IF NOT EXISTS idx_mcp_server_created_at  ON mcp_server (created_at DESC);

-- ---------------------------------------------------------------------
-- 2. agent.allowed_mcps: 与 allowed_tools / allowed_skills 并存
--    非空时, Factory 在组装 ToolCallback 时合并远端 MCP 工具
-- ---------------------------------------------------------------------
ALTER TABLE agent ADD COLUMN IF NOT EXISTS allowed_mcps JSONB;

-- ---------------------------------------------------------------------
-- 3. tool_call_trace.source: 区分工具调用来源
--    LOCAL          : 本地 Spring bean (Tool 接口实现)
--    MCP:<serverId> : 远端 MCP 服务器返回的工具
--    旧数据为 NULL, 前端按 NULL 当 LOCAL 显示
-- ---------------------------------------------------------------------
ALTER TABLE tool_call_trace ADD COLUMN IF NOT EXISTS source VARCHAR(96);
CREATE INDEX IF NOT EXISTS idx_tool_call_trace_source ON tool_call_trace (source);
