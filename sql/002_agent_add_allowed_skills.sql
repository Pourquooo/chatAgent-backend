-- =====================================================================
-- Phase 2: Skill 系统 - Agent 表加 allowed_skills 列
--
-- 语义: 与 allowed_tools 并存.
--   allowed_skills 非空  -> Factory 走 Skill 激活逻辑
--   allowed_skills 为空  -> 降级到 allowed_tools (向后兼容)
-- =====================================================================

ALTER TABLE agent ADD COLUMN IF NOT EXISTS allowed_skills JSONB;
