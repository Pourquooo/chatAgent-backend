-- =====================================================================
-- Phase 1: Agent 可观测性 - Trace 表结构
--
-- 一次用户提问 → 1 条 agent_trace
--   └─ 一个 Think-Execute 步骤 → 1 条 agent_step_trace
--       └─ 一次工具调用 → 1 条 tool_call_trace
--
-- 设计取舍:
--   1. 不加外键约束: trace 数据本质是"日志", 不应阻塞主流程;
--      清理策略靠应用层或定时任务而非 ON DELETE CASCADE
--   2. status / phase 用 VARCHAR 而非 PG enum, 与现有 chat_message.role 风格一致
--   3. 主键 UUID, 由数据库 gen_random_uuid() 生成, 对齐现有 agent / chat_message 表
--   4. arguments 用 jsonb 便于结构化查询; result 用 text 因可能很长 (RAG 片段等)
-- =====================================================================

-- 启用 UUID 生成函数 (PostgreSQL 13+ 自带 gen_random_uuid, 依赖 pgcrypto)
CREATE EXTENSION IF NOT EXISTS pgcrypto;

-- =====================================================================
-- 1. agent_trace : 一次用户提问对应的完整 Agent 执行链路
-- =====================================================================
CREATE TABLE IF NOT EXISTS agent_trace (
    id                       UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    session_id               UUID        NOT NULL,
    agent_id                 UUID        NOT NULL,
    user_message             TEXT,                               -- 触发本次 trace 的用户消息(截断展示用)
    status                   VARCHAR(32) NOT NULL DEFAULT 'RUNNING',  -- RUNNING / FINISHED / ERROR
    total_steps              INTEGER              DEFAULT 0,
    total_latency_ms         BIGINT               DEFAULT 0,
    total_prompt_tokens      INTEGER              DEFAULT 0,
    total_completion_tokens  INTEGER              DEFAULT 0,
    error_message            TEXT,
    started_at               TIMESTAMP   NOT NULL DEFAULT NOW(),
    finished_at              TIMESTAMP,
    created_at               TIMESTAMP   NOT NULL DEFAULT NOW(),
    updated_at               TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_agent_trace_session_id  ON agent_trace (session_id);
CREATE INDEX IF NOT EXISTS idx_agent_trace_agent_id    ON agent_trace (agent_id);
CREATE INDEX IF NOT EXISTS idx_agent_trace_started_at  ON agent_trace (started_at DESC);
CREATE INDEX IF NOT EXISTS idx_agent_trace_status      ON agent_trace (status);

-- =====================================================================
-- 2. agent_step_trace : 单个 Think-Execute 步骤
-- =====================================================================
CREATE TABLE IF NOT EXISTS agent_step_trace (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trace_id           UUID        NOT NULL,
    step_index         INTEGER     NOT NULL,                  -- 第几步 (1-based)
    phase              VARCHAR(32) NOT NULL,                  -- THINK / EXECUTE
    model_name         VARCHAR(64),                           -- 使用的模型名, 如 deepseek-chat
    prompt_tokens      INTEGER     DEFAULT 0,
    completion_tokens  INTEGER     DEFAULT 0,
    latency_ms         BIGINT      DEFAULT 0,
    status             VARCHAR(32) NOT NULL DEFAULT 'RUNNING',-- RUNNING / SUCCESS / ERROR
    error_message      TEXT,
    started_at         TIMESTAMP   NOT NULL DEFAULT NOW(),
    finished_at        TIMESTAMP,
    created_at         TIMESTAMP   NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_agent_step_trace_trace_id   ON agent_step_trace (trace_id);
CREATE INDEX IF NOT EXISTS idx_agent_step_trace_started_at ON agent_step_trace (started_at);

-- =====================================================================
-- 3. tool_call_trace : 单次工具调用
-- =====================================================================
CREATE TABLE IF NOT EXISTS tool_call_trace (
    id             UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    trace_id       UUID         NOT NULL,
    step_id        UUID         NOT NULL,
    tool_name      VARCHAR(128) NOT NULL,
    arguments      JSONB,                                     -- 工具入参
    result         TEXT,                                      -- 工具输出 (可能很长)
    latency_ms     BIGINT       DEFAULT 0,
    status         VARCHAR(32)  NOT NULL DEFAULT 'RUNNING',   -- RUNNING / SUCCESS / ERROR
    error_message  TEXT,
    started_at     TIMESTAMP    NOT NULL DEFAULT NOW(),
    finished_at    TIMESTAMP,
    created_at     TIMESTAMP    NOT NULL DEFAULT NOW()
);

CREATE INDEX IF NOT EXISTS idx_tool_call_trace_trace_id  ON tool_call_trace (trace_id);
CREATE INDEX IF NOT EXISTS idx_tool_call_trace_step_id   ON tool_call_trace (step_id);
CREATE INDEX IF NOT EXISTS idx_tool_call_trace_tool_name ON tool_call_trace (tool_name);
