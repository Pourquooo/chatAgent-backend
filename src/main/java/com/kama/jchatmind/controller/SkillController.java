package com.kama.jchatmind.controller;

import com.kama.jchatmind.model.common.ApiResponse;
import com.kama.jchatmind.skill.SkillMetadata;
import com.kama.jchatmind.skill.SkillRegistry;
import lombok.AllArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
@AllArgsConstructor
public class SkillController {

    private final SkillRegistry skillRegistry;

    // 返回所有已加载的 skill 元数据, 供前端 Agent 配置页选择
    @GetMapping("/skills")
    public ApiResponse<List<SkillMetadata>> listSkills() {
        return ApiResponse.success(skillRegistry.getAll());
    }
}
