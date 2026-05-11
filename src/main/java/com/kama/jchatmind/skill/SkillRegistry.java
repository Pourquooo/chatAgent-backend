package com.kama.jchatmind.skill;

import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.core.io.support.ResourcePatternResolver;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.Constructor;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Skill 注册中心 - 启动时扫描 classpath:skills/&#42;&#42;/skill.yaml, 维护全量 Skill 元数据,
 * 并提供"按关键词匹配"的激活查询.
 */
@Slf4j
@Component
public class SkillRegistry {

    private static final String SKILL_LOCATION_PATTERN = "classpath*:skills/*/skill.yaml";

    // 保持插入顺序, 对外展示稳定
    private final Map<String, SkillMetadata> skillsById = new LinkedHashMap<>();

    @PostConstruct
    public void load() {
        ResourcePatternResolver resolver = new PathMatchingResourcePatternResolver();
        Resource[] resources;
        try {
            resources = resolver.getResources(SKILL_LOCATION_PATTERN);
        } catch (Exception e) {
            log.warn("[skill] scan classpath failed, no skill loaded", e);
            return;
        }
        if (resources.length == 0) {
            log.info("[skill] no skill.yaml under classpath:skills/, skipping");
            return;
        }

        Yaml yaml = new Yaml(new Constructor(SkillMetadata.class, new LoaderOptions()));
        for (Resource r : resources) {
            try (InputStream in = r.getInputStream()) {
                SkillMetadata meta = yaml.load(in);
                if (meta == null || !StringUtils.hasText(meta.getId())) {
                    log.warn("[skill] skipped (id missing): {}", r.getDescription());
                    continue;
                }
                if (skillsById.containsKey(meta.getId())) {
                    log.warn("[skill] duplicate id '{}', keeping first occurrence", meta.getId());
                    continue;
                }
                skillsById.put(meta.getId(), meta);
                log.info("[skill] loaded '{}' ({} tools, {} triggers)",
                        meta.getId(),
                        meta.getTools() == null ? 0 : meta.getTools().size(),
                        meta.getTriggers() == null ? 0 : meta.getTriggers().size());
            } catch (Exception e) {
                log.warn("[skill] parse failed: {}", r.getDescription(), e);
            }
        }
        log.info("[skill] total loaded = {}", skillsById.size());
    }

    public List<SkillMetadata> getAll() {
        return new ArrayList<>(skillsById.values());
    }

    public SkillMetadata getById(String id) {
        if (id == null) return null;
        return skillsById.get(id);
    }

    /**
     * 按候选 skillId 列表与用户消息, 决定本次要激活的 skill.
     *   1. 剔除不存在的 id;
     *   2. 若消息命中任一候选的 trigger, 仅激活命中的;
     *   3. 若没有命中, 返回全部候选 (兜底, 让 agent 配置的能力都能用).
     */
    public List<SkillMetadata> activate(Collection<String> candidateSkillIds, String userMessage) {
        if (candidateSkillIds == null || candidateSkillIds.isEmpty()) {
            return Collections.emptyList();
        }
        List<SkillMetadata> candidates = candidateSkillIds.stream()
                .map(this::getById)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
        if (candidates.isEmpty()) {
            return Collections.emptyList();
        }

        String normalized = userMessage == null ? "" : userMessage.toLowerCase(Locale.ROOT);
        if (normalized.isEmpty()) {
            return candidates;
        }

        List<SkillMetadata> matched = candidates.stream()
                .filter(s -> hasTriggerHit(s, normalized))
                .collect(Collectors.toList());
        if (matched.isEmpty()) {
            log.debug("[skill] no trigger hit, fall back to all candidates ({})", candidates.size());
            return candidates;
        }
        Set<String> matchedIds = matched.stream().map(SkillMetadata::getId).collect(Collectors.toSet());
        log.debug("[skill] matched {} by triggers: {}", matched.size(), matchedIds);
        return matched;
    }

    private boolean hasTriggerHit(SkillMetadata skill, String normalizedMessage) {
        List<String> triggers = skill.getTriggers();
        if (triggers == null || triggers.isEmpty()) return false;
        for (String t : triggers) {
            if (!StringUtils.hasText(t)) continue;
            if (normalizedMessage.contains(t.toLowerCase(Locale.ROOT))) {
                return true;
            }
        }
        return false;
    }
}
