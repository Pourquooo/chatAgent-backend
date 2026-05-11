package com.kama.jchatmind.skill;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * Skill 元数据 - 从 skills/&lt;id&gt;/skill.yaml 反序列化而来.
 * YAML 的 key 用 camelCase, SnakeYAML 默认不做命名转换.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SkillMetadata {

    /** 唯一标识, 建议与目录名一致. */
    private String id;

    /** 展示名, 供前端 Agent 配置选择. */
    private String name;

    /** 描述. */
    private String description;

    /** 版本号. */
    private String version;

    /**
     * 激活关键词. 用户消息包含任一关键词即命中 (大小写不敏感).
     * 为空时, 该 skill 只能作为候选被"兜底激活".
     */
    private List<String> triggers;

    /** 激活后追加到 think 系统提示词的片段, 引导 LLM 如何使用本 skill 的工具. */
    private String systemPromptFragment;

    /**
     * 本 skill 关联的 Tool Bean 名称列表 (对应 {@code Tool.getName()}).
     */
    private List<String> tools;
}
