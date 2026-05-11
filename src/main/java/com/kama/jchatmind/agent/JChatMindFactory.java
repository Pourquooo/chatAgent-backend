package com.kama.jchatmind.agent;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.kama.jchatmind.agent.tools.Tool;
import com.kama.jchatmind.config.ChatClientRegistry;
import com.kama.jchatmind.converter.AgentConverter;
import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.converter.KnowledgeBaseConverter;
import com.kama.jchatmind.mapper.AgentMapper;
import com.kama.jchatmind.mapper.KnowledgeBaseMapper;
import com.kama.jchatmind.model.dto.AgentDTO;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.entity.KnowledgeBase;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.ToolFacadeService;
import com.kama.jchatmind.service.TraceService;
import com.kama.jchatmind.skill.SkillMetadata;
import com.kama.jchatmind.skill.SkillRegistry;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.ai.tool.method.MethodToolCallbackProvider;
import org.springframework.aop.support.AopUtils;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Component
public class JChatMindFactory {

    private static final Logger log = LoggerFactory.getLogger(JChatMindFactory.class);
    private final ChatClientRegistry chatClientRegistry;
    private final SseService sseService;
    private final AgentMapper agentMapper;
    private final AgentConverter agentConverter;
    private final KnowledgeBaseMapper knowledgeBaseMapper;
    private final KnowledgeBaseConverter knowledgeBaseConverter;
    private final ToolFacadeService toolFacadeService;
    private final ChatMessageFacadeService chatMessageFacadeService;
    private final ChatMessageConverter chatMessageConverter;
    private final TraceService traceService;
    private final SkillRegistry skillRegistry;

    // 运行时 Agent 配置
    private AgentDTO agentConfig;

    public JChatMindFactory(
            ChatClientRegistry chatClientRegistry,
            SseService sseService,
            AgentMapper agentMapper,
            AgentConverter agentConverter,
            KnowledgeBaseMapper knowledgeBaseMapper,
            KnowledgeBaseConverter knowledgeBaseConverter,
            ToolFacadeService toolFacadeService,
            ChatMessageFacadeService chatMessageFacadeService,
            ChatMessageConverter chatMessageConverter,
            TraceService traceService,
            SkillRegistry skillRegistry
    ) {
        this.chatClientRegistry = chatClientRegistry;
        this.sseService = sseService;
        this.agentMapper = agentMapper;
        this.agentConverter = agentConverter;
        this.knowledgeBaseMapper = knowledgeBaseMapper;
        this.knowledgeBaseConverter = knowledgeBaseConverter;
        this.toolFacadeService = toolFacadeService;
        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageConverter = chatMessageConverter;
        this.traceService = traceService;
        this.skillRegistry = skillRegistry;
    }

    private Agent loadAgent(String agentId) {
        return agentMapper.selectById(agentId);
    }

    /**
     * 将数据库中存储的记忆恢复成 List<Message> 结构
     */
    private List<Message> loadMemory(String chatSessionId) {
        int messageLength = agentConfig.getChatOptions().getMessageLength();
        List<ChatMessageDTO> chatMessages = chatMessageFacadeService.getChatMessagesBySessionIdRecently(chatSessionId, messageLength);
        List<Message> memory = new ArrayList<>();
        for (ChatMessageDTO chatMessageDTO : chatMessages) {
            switch (chatMessageDTO.getRole()) {
                case SYSTEM:
                    if (!StringUtils.hasLength(chatMessageDTO.getContent())) continue;
                    memory.add(0, new SystemMessage(chatMessageDTO.getContent()));
                    break;
                case USER:
                    if (!StringUtils.hasLength(chatMessageDTO.getContent())) continue;
                    memory.add(new UserMessage(chatMessageDTO.getContent()));
                    break;
                case ASSISTANT:
                    memory.add(AssistantMessage.builder()
                            .content(chatMessageDTO.getContent())
                            .toolCalls(chatMessageDTO.getMetadata()
                                    .getToolCalls())
                            .build());
                    break;
                case TOOL:
                    memory.add(ToolResponseMessage.builder()
                            .responses(List.of(chatMessageDTO
                                    .getMetadata()
                                    .getToolResponse()))
                            .build());
                    break;
                default:
                    log.error("不支持的 Message 类型: {}, content = {}",
                            chatMessageDTO.getRole().getRole(),
                            chatMessageDTO.getContent()
                    );
                    throw new IllegalStateException("不支持的 Message 类型");
            }
        }
        trimDanglingToolCalls(memory);
        return memory;
    }

    /**
     * 清理头部: 滑动窗口 / 历史残缺可能导致 assistant(tool_calls) 与 tool(response) 配对不全,
     * 而 DeepSeek 等 API 会返回 400 "insufficient tool messages". 这里在头部丢掉:
     *   1. 孤儿 tool response (前置 assistant 被截掉)
     *   2. 后续 tool responses 不足以覆盖其 tool_calls 的 assistant.
     */
    private void trimDanglingToolCalls(List<Message> memory) {
        while (!memory.isEmpty()) {
            Message head = memory.get(0);
            if (head instanceof ToolResponseMessage) {
                log.info("[memory] drop dangling tool response at head");
                memory.remove(0);
                continue;
            }
            if (head instanceof AssistantMessage am) {
                List<AssistantMessage.ToolCall> calls = am.getToolCalls();
                if (calls != null && !calls.isEmpty() && !hasAllToolResponses(memory, calls)) {
                    log.info("[memory] drop assistant with incomplete tool responses at head");
                    memory.remove(0);
                    continue;
                }
            }
            break;
        }
    }

    private boolean hasAllToolResponses(List<Message> memory, List<AssistantMessage.ToolCall> calls) {
        Set<String> required = new HashSet<>();
        for (AssistantMessage.ToolCall c : calls) {
            required.add(c.id());
        }
        for (int i = 1; i < memory.size() && !required.isEmpty(); i++) {
            Message m = memory.get(i);
            if (m instanceof ToolResponseMessage trm) {
                for (ToolResponseMessage.ToolResponse resp : trm.getResponses()) {
                    required.remove(resp.id());
                }
            } else {
                break;
            }
        }
        return required.isEmpty();
    }

    private AgentDTO toAgentConfig(Agent agent) {
        try {
            agentConfig = agentConverter.toDTO(agent);
            return agentConfig;
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("解析 Agent 配置失败", e);
        }
    }

    private List<KnowledgeBaseDTO> resolveRuntimeKnowledgeBases(AgentDTO agentConfig) {
        List<String> allowedKbIds = agentConfig.getAllowedKbs();
        if (allowedKbIds == null || allowedKbIds.isEmpty()) {
            return Collections.emptyList();
        }

        List<KnowledgeBase> knowledgeBases = knowledgeBaseMapper.selectByIdBatch(allowedKbIds);
        if (knowledgeBases.isEmpty()) {
            return Collections.emptyList();
        }
        List<KnowledgeBaseDTO> kbDTOs = new ArrayList<>();
        try {
            for (KnowledgeBase knowledgeBase : knowledgeBases) {
                KnowledgeBaseDTO kbDTO = knowledgeBaseConverter.toDTO(knowledgeBase);
                kbDTOs.add(kbDTO);
            }
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
        return kbDTOs;
    }

    /** 一次 run 的 skill 解析结果: 本次激活的工具集 + 拼好的提示词片段. */
    private record SkillResolveResult(List<Tool> tools, String promptFragment) {}

    private SkillResolveResult resolveRuntimeTools(AgentDTO agentConfig, String userMessage) {
        // 固定工具（系统强制, 无论什么路径都加上）
        List<Tool> runtimeTools = new ArrayList<>(toolFacadeService.getFixedTools());

        Map<String, Tool> optionalToolMap = toolFacadeService.getOptionalTools()
                .stream()
                .collect(Collectors.toMap(Tool::getName, Function.identity()));

        List<String> allowedSkillIds = agentConfig.getAllowedSkills();

        // ===== Skill 路径: allowedSkills 非空时按关键词激活 =====
        if (allowedSkillIds != null && !allowedSkillIds.isEmpty()) {
            List<SkillMetadata> activated = skillRegistry.activate(allowedSkillIds, userMessage);

            Set<String> seenToolNames = new HashSet<>();
            StringBuilder promptBuilder = new StringBuilder();
            for (SkillMetadata skill : activated) {
                if (skill.getTools() != null) {
                    for (String toolName : skill.getTools()) {
                        if (!seenToolNames.add(toolName)) continue;
                        Tool tool = optionalToolMap.get(toolName);
                        if (tool != null) {
                            runtimeTools.add(tool);
                        } else {
                            log.warn("[skill] '{}' references missing tool bean '{}'", skill.getId(), toolName);
                        }
                    }
                }
                if (StringUtils.hasText(skill.getSystemPromptFragment())) {
                    promptBuilder.append("- [").append(skill.getId()).append("] ")
                            .append(skill.getSystemPromptFragment().trim())
                            .append("\n");
                }
            }

            log.info("[skill] agent={} activated {} skill(s), {} optional tool(s)",
                    agentConfig.getId(), activated.size(), seenToolNames.size());

            return new SkillResolveResult(runtimeTools, promptBuilder.toString());
        }

        // ===== 降级路径: 老 agent 仍按 allowedTools 精细控制 =====
        List<String> allowedToolNames = agentConfig.getAllowedTools();
        if (allowedToolNames != null && !allowedToolNames.isEmpty()) {
            for (String toolName : allowedToolNames) {
                Tool tool = optionalToolMap.get(toolName);
                if (tool != null) {
                    runtimeTools.add(tool);
                }
            }
        }
        return new SkillResolveResult(runtimeTools, null);
    }

    private String extractLatestUserMessage(List<Message> memory) {
        if (memory == null) return null;
        for (int i = memory.size() - 1; i >= 0; i--) {
            Message m = memory.get(i);
            if (m instanceof UserMessage um) {
                return um.getText();
            }
        }
        return null;
    }

    private List<ToolCallback> buildToolCallbacks(List<Tool> runtimeTools) {
        List<ToolCallback> callbacks = new ArrayList<>();
        for (Tool tool : runtimeTools) {
            Object target = resolveToolTarget(tool);
            ToolCallback[] toolCallbacks = MethodToolCallbackProvider.builder()
                    .toolObjects(target)
                    .build()
                    .getToolCallbacks();
            callbacks.addAll(Arrays.asList(toolCallbacks));
        }
        return callbacks;
    }

    private Object resolveToolTarget(Tool tool) {
        try {
            return AopUtils.isAopProxy(tool)
                    ? AopUtils.getTargetClass(tool)
                    : tool;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "解析工具目标对象失败: " + tool.getName(), e);
        }
    }

    private JChatMind buildAgentRuntime(
            Agent agent,
            List<Message> memory,
            List<KnowledgeBaseDTO> knowledgeBases,
            List<ToolCallback> toolCallbacks,
            String chatSessionId
    ) {
        ChatClient chatClient = chatClientRegistry.get(agent.getModel());
        if (Objects.isNull(chatClient)) {
            throw new IllegalStateException("未找到对应的 ChatClient: " + agent.getModel());
        }
        return new JChatMind(
                agent.getId(),
                agent.getName(),
                agent.getDescription(),
                agent.getSystemPrompt(),
                chatClient,
                agent.getModel(),
                agentConfig.getChatOptions().getMessageLength(),
                memory,
                toolCallbacks,
                knowledgeBases,
                chatSessionId,
                sseService,
                chatMessageFacadeService,
                chatMessageConverter,
                traceService
        );
    }

    /**
     * 创建一个 JChatMind 实例
     */
    public JChatMind create(String agentId, String chatSessionId) {
        Agent agent = loadAgent(agentId);
        AgentDTO agentConfig = toAgentConfig(agent);
        List<Message> memory = loadMemory(chatSessionId);

        // 解析 agent 的支持的知识库
        List<KnowledgeBaseDTO> knowledgeBases = resolveRuntimeKnowledgeBases(agentConfig);
        // 从 memory 中取最近一条 UserMessage, 驱动 Skill 的关键词激活
        String latestUserMessage = extractLatestUserMessage(memory);
        // 解析 agent 支持的工具调用 + skill 提示片段
        SkillResolveResult resolved = resolveRuntimeTools(agentConfig, latestUserMessage);
        // 将工具调用转换成 ToolCallback 的形式
        List<ToolCallback> toolCallbacks = buildToolCallbacks(resolved.tools());

        JChatMind jChatMind = buildAgentRuntime(
                agent,
                memory,
                knowledgeBases,
                toolCallbacks,
                chatSessionId
        );
        jChatMind.setRuntimeSkillPrompt(resolved.promptFragment());
        return jChatMind;
    }
}
