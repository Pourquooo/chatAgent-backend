package com.kama.jchatmind.agent;

import com.kama.jchatmind.converter.ChatMessageConverter;
import com.kama.jchatmind.message.SseMessage;
import com.kama.jchatmind.model.dto.ChatMessageDTO;
import com.kama.jchatmind.model.dto.KnowledgeBaseDTO;
import com.kama.jchatmind.model.entity.AgentStepTrace;
import com.kama.jchatmind.model.entity.AgentTrace;
import com.kama.jchatmind.model.entity.ToolCallTrace;
import com.kama.jchatmind.model.response.CreateChatMessageResponse;
import com.kama.jchatmind.model.vo.ChatMessageVO;
import com.kama.jchatmind.service.ChatMessageFacadeService;
import com.kama.jchatmind.service.SseService;
import com.kama.jchatmind.service.TraceService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.memory.ChatMemory;
import org.springframework.ai.chat.memory.MessageWindowChatMemory;
import org.springframework.ai.chat.messages.*;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.ai.model.tool.DefaultToolCallingChatOptions;
import org.springframework.ai.model.tool.ToolCallingManager;
import org.springframework.ai.model.tool.ToolExecutionResult;
import org.springframework.ai.tool.ToolCallback;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

@Slf4j
public class JChatMind {
    // 智能体 ID
    private String agentId;

    // 名称
    private String name;

    // 描述
    private String description;

    // 默认系统提示词
    private String systemPrompt;

    // 交互实例
    private ChatClient chatClient;

    // 使用的模型名称（trace 记录用）
    private String modelName;

    // 状态
    private AgentState agentState;

    // 可用的工具
    private List<ToolCallback> availableTools;

    // 可访问的知识库
    private List<KnowledgeBaseDTO> availableKbs;

    // 工具调用管理器
    private ToolCallingManager toolCallingManager;

    // 模型的聊天记录
    private ChatMemory chatMemory;

    // 模型的聊天会话 ID
    private String chatSessionId;

    // 最多循环次数
    private static final Integer MAX_STEPS = 20;

    private static final Integer DEFAULT_MAX_MESSAGES = 20;

    // SpringAI 自带的 ChatOptions, 不是 AgentDTO.ChatOptions
    private ChatOptions chatOptions;

    // SSE 服务, 用于发送消息给前端
    private SseService sseService;

    private ChatMessageConverter chatMessageConverter;

    private ChatMessageFacadeService chatMessageFacadeService;

    // 最后一次的 ChatResponse
    private ChatResponse lastChatResponse;

    // AI 返回的，已经持久化，但是需要 sse 发给前端的消息
    private final List<ChatMessageDTO> pendingChatMessages = new ArrayList<>();

    // ========== Trace 相关字段 ==========
    private TraceService traceService;
    private String traceId;               // 本次 run() 的 trace id（startTrace 失败则为 null）
    private int totalPromptTokens;        // 累计 prompt tokens
    private int totalCompletionTokens;    // 累计 completion tokens

    // ========== Skill 相关字段 ==========
    // 本次 run 激活的 skill 提示片段 (已拼接), 仅注入到 think 提示词, 不进聊天记忆.
    // 由 JChatMindFactory 在构造实例后通过 setter 注入.
    private String runtimeSkillPrompt;

    public void setRuntimeSkillPrompt(String runtimeSkillPrompt) {
        this.runtimeSkillPrompt = runtimeSkillPrompt;
    }

    public JChatMind() {
    }

    public JChatMind(String agentId,
                     String name,
                     String description,
                     String systemPrompt,
                     ChatClient chatClient,
                     String modelName,
                     Integer maxMessages,
                     List<Message> memory,
                     List<ToolCallback> availableTools,
                     List<KnowledgeBaseDTO> availableKbs,
                     String chatSessionId,
                     SseService sseService,
                     ChatMessageFacadeService chatMessageFacadeService,
                     ChatMessageConverter chatMessageConverter,
                     TraceService traceService
    ) {
        this.agentId = agentId;
        this.name = name;
        this.description = description;
        this.systemPrompt = systemPrompt;

        this.chatClient = chatClient;
        this.modelName = modelName;

        this.availableTools = availableTools;
        this.availableKbs = availableKbs;

        this.chatSessionId = chatSessionId;
        this.sseService = sseService;

        this.chatMessageFacadeService = chatMessageFacadeService;
        this.chatMessageConverter = chatMessageConverter;

        this.traceService = traceService;

        this.agentState = AgentState.IDLE;

        // 保存聊天记录
        this.chatMemory = MessageWindowChatMemory.builder()
                .maxMessages(maxMessages == null ? DEFAULT_MAX_MESSAGES : maxMessages)
                .build();
        this.chatMemory.add(chatSessionId, memory);

        // 添加系统提示
        if (StringUtils.hasLength(systemPrompt)) {
            this.chatMemory.add(chatSessionId, new SystemMessage(systemPrompt));
        }

        // 关闭 SpringAI 自带的内部的工具调用自动执行功能
        this.chatOptions = DefaultToolCallingChatOptions.builder()
                .internalToolExecutionEnabled(false)
                .build();

        // 工具调用管理器
        this.toolCallingManager = ToolCallingManager.builder().build();
    }

    // 打印工具调用信息
    private void logToolCalls(List<AssistantMessage.ToolCall> toolCalls) {
        if (toolCalls == null || toolCalls.isEmpty()) {
            log.info("\n\n[ToolCalling] 无工具调用");
            return;
        }
        String logMessage = IntStream.range(0, toolCalls.size())
                .mapToObj(i -> {
                    AssistantMessage.ToolCall call = toolCalls.get(i);
                    return String.format(
                            "[ToolCalling #%d]\n- name      : %s\n- arguments : %s",
                            i + 1,
                            call.name(),
                            call.arguments()
                    );
                })
                .collect(Collectors.joining("\n\n"));
        log.info("\n\n========== Tool Calling ==========\n{}\n=================================\n", logMessage);
    }

    // 持久化 Message, 返回 chatMessageId
    // 需要 Agent 持久化的 Message 子类有以下两类
    // AssistantMessage
    // ToolResponseMessage

    // SystemMessage 不需要持久化
    // UserMessage 在每次用户发送问题之间就已经持久化过了
    private void saveMessage(Message message) {
        ChatMessageDTO.ChatMessageDTOBuilder builder = ChatMessageDTO.builder();
        if (message instanceof AssistantMessage assistantMessage) {
            ChatMessageDTO chatMessageDTO = builder.role(ChatMessageDTO.RoleType.ASSISTANT)
                    .content(assistantMessage.getText())
                    .sessionId(this.chatSessionId)
                    .metadata(ChatMessageDTO.MetaData.builder()
                            .toolCalls(assistantMessage.getToolCalls())
                            .build())
                    .build();
            CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(chatMessageDTO);
            chatMessageDTO.setId(chatMessage.getChatMessageId());
            pendingChatMessages.add(chatMessageDTO);
        } else if (message instanceof ToolResponseMessage toolResponseMessage) {
            // 持久化 ToolResponseMessage
            for (ToolResponseMessage.ToolResponse toolResponse : toolResponseMessage.getResponses()) {
                ChatMessageDTO chatMessageDTO = builder.role(ChatMessageDTO.RoleType.TOOL)
                        .content(toolResponse.responseData())
                        .sessionId(this.chatSessionId)
                        .metadata(ChatMessageDTO.MetaData.builder()
                                .toolResponse(toolResponse)
                                .build())
                        .build();
                CreateChatMessageResponse chatMessage = chatMessageFacadeService.createChatMessage(chatMessageDTO);
                chatMessageDTO.setId(chatMessage.getChatMessageId());
                pendingChatMessages.add(chatMessageDTO);
            }
        } else {
            throw new IllegalArgumentException("不支持的 Message 类型: " + message.getClass().getName());
        }
    }

    // 刷新 pendingMessages, 将数据通过 sse 发送给前端
    private void refreshPendingMessages() {
        for (ChatMessageDTO message : pendingChatMessages) {
            ChatMessageVO vo = chatMessageConverter.toVO(message);
            SseMessage sseMessage = SseMessage.builder()
                    .type(SseMessage.Type.AI_GENERATED_CONTENT)
                    .payload(SseMessage.Payload.builder()
                            .message(vo)
                            .build())
                    .metadata(SseMessage.Metadata.builder()
                            .chatMessageId(message.getId())
                            .build())
                    .build();
            sseService.send(this.chatSessionId, sseMessage);
        }
        pendingChatMessages.clear();
    }

    // thinkPrompt 应该放到 system 中还是
    private boolean think(int stepIndex) {
        long startNanos = System.nanoTime();
        AgentStepTrace thinkStep = traceService.startStep(
                traceId, stepIndex, TraceService.PHASE_THINK, modelName);
        String thinkStepId = thinkStep == null ? null : thinkStep.getId();

        try {
            String thinkPrompt = """
                    现在你是一个智能的的具体「决策模块」
                    请根据当前对话上下文，决定下一步的动作。
                                    \s
                    【额外信息】
                    - 你目前拥有的知识库列表以及描述：%s
                    - 如果有缺失的上下文时，优先从知识库中进行搜索
                    %s
                    """.formatted(
                    this.availableKbs,
                    StringUtils.hasText(this.runtimeSkillPrompt)
                            ? "【本次激活的技能】\n" + this.runtimeSkillPrompt
                            : ""
            );

            // 将 thinkPrompt 通过 .user(thinkPrompt) 的方式构造进入 chatClient 中
            // 既能让每次 messageList 的最后一条是 本条提示词，
            // 又能够避免将 thinkPrompt 加入到聊天记录中
            Prompt prompt = Prompt.builder()
                    .chatOptions(this.chatOptions)
                    .messages(this.chatMemory.get(this.chatSessionId))
                    .build();

            this.lastChatResponse = this.chatClient
                    .prompt(prompt)
                    .system(thinkPrompt)
                    .toolCallbacks(this.availableTools.toArray(new ToolCallback[0]))
                    .call()
                    .chatClientResponse()
                    .chatResponse();

            Assert.notNull(lastChatResponse, "Last chat client response cannot be null");

            AssistantMessage output = this.lastChatResponse
                    .getResult()
                    .getOutput();

            List<AssistantMessage.ToolCall> toolCalls = output.getToolCalls();

            // 保存
            saveMessage(output);
            refreshPendingMessages();

            // 打印工具调用
            logToolCalls(toolCalls);

            int[] tokens = extractTokens(this.lastChatResponse);
            totalPromptTokens += tokens[0];
            totalCompletionTokens += tokens[1];

            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            traceService.endStep(thinkStepId, TraceService.STATUS_SUCCESS,
                    tokens[0], tokens[1], latencyMs, null);

            // 如果工具调用不为空，则进入执行阶段
            return !toolCalls.isEmpty();
        } catch (RuntimeException e) {
            long latencyMs = (System.nanoTime() - startNanos) / 1_000_000L;
            traceService.endStep(thinkStepId, TraceService.STATUS_ERROR,
                    0, 0, latencyMs, e.getMessage());
            throw e;
        }
    }

    private int[] extractTokens(ChatResponse response) {
        if (response == null || response.getMetadata() == null) return new int[]{0, 0};
        Usage usage = response.getMetadata().getUsage();
        if (usage == null) return new int[]{0, 0};
        int pt = usage.getPromptTokens() != null ? usage.getPromptTokens().intValue() : 0;
        int ct = usage.getCompletionTokens() != null ? usage.getCompletionTokens().intValue() : 0;
        return new int[]{pt, ct};
    }

    // 执行
    private void execute(int stepIndex) {
        Assert.notNull(this.lastChatResponse, "Last chat client response cannot be null");

        if (!this.lastChatResponse.hasToolCalls()) {
            return;
        }

        long stepStart = System.nanoTime();
        AgentStepTrace execStep = traceService.startStep(
                traceId, stepIndex, TraceService.PHASE_EXECUTE, null);
        String execStepId = execStep == null ? null : execStep.getId();

        // 预登记每个 ToolCall: tool_call_id (模型返回) -> 我们持久化的 tool_call_trace id
        List<AssistantMessage.ToolCall> outgoing = this.lastChatResponse
                .getResult().getOutput().getToolCalls();
        Map<String, String> toolCallIdToTraceId = new HashMap<>();
        for (AssistantMessage.ToolCall tc : outgoing) {
            ToolCallTrace traced = traceService.startToolCall(
                    traceId, execStepId, tc.name(), tc.arguments());
            if (traced != null) {
                toolCallIdToTraceId.put(tc.id(), traced.getId());
            }
        }

        try {
            Prompt prompt = Prompt.builder()
                    .messages(this.chatMemory.get(this.chatSessionId))
                    .chatOptions(this.chatOptions)
                    .build();

            ToolExecutionResult toolExecutionResult = toolCallingManager.executeToolCalls(prompt, this.lastChatResponse);

            this.chatMemory.clear(this.chatSessionId);
            this.chatMemory.add(this.chatSessionId, toolExecutionResult.conversationHistory());

            ToolResponseMessage toolResponseMessage = (ToolResponseMessage) toolExecutionResult
                    .conversationHistory()
                    .get(toolExecutionResult.conversationHistory().size() - 1);

            String collect = toolResponseMessage.getResponses()
                    .stream()
                    .map(resp -> "工具" + resp.name() + "的返回结果为：" + resp.responseData())
                    .collect(Collectors.joining("\n"));

            log.info("工具调用结果：{}", collect);

            // 将工具响应回写到对应的 tool_call_trace
            for (ToolResponseMessage.ToolResponse resp : toolResponseMessage.getResponses()) {
                String tcTraceId = toolCallIdToTraceId.remove(resp.id());
                if (tcTraceId != null) {
                    traceService.endToolCall(tcTraceId,
                            TraceService.STATUS_SUCCESS, resp.responseData(), null, null);
                }
            }
            // 没有对应响应的 tool_call（罕见，防御性处理）
            for (String leftover : toolCallIdToTraceId.values()) {
                traceService.endToolCall(leftover,
                        TraceService.STATUS_ERROR, null, null, "no tool response received");
            }

            // 保存工具调用
            saveMessage(toolResponseMessage);
            refreshPendingMessages();

            if (toolResponseMessage.getResponses()
                    .stream()
                    .anyMatch(resp -> resp.name().equals("terminate"))) {
                this.agentState = AgentState.FINISHED;
                log.info("任务结束");
            }

            long latencyMs = (System.nanoTime() - stepStart) / 1_000_000L;
            traceService.endStep(execStepId, TraceService.STATUS_SUCCESS, 0, 0, latencyMs, null);
        } catch (RuntimeException e) {
            long latencyMs = (System.nanoTime() - stepStart) / 1_000_000L;
            // 未能完成的 tool_call 标记为 ERROR
            for (String leftover : toolCallIdToTraceId.values()) {
                traceService.endToolCall(leftover,
                        TraceService.STATUS_ERROR, null, null, e.getMessage());
            }
            traceService.endStep(execStepId, TraceService.STATUS_ERROR,
                    0, 0, latencyMs, e.getMessage());
            throw e;
        }
    }

    // 单个步骤模板
    private void step(int stepIndex) {
        if (think(stepIndex)) {
            execute(stepIndex);
        } else { // 没有工具调用
            agentState = AgentState.FINISHED;
        }
    }

    // 运行
    public void run() {
        if (agentState != AgentState.IDLE) {
            throw new IllegalStateException("Agent is not idle");
        }

        long runStart = System.nanoTime();
        AgentTrace trace = traceService.startTrace(chatSessionId, agentId, extractLastUserMessage());
        this.traceId = trace == null ? null : trace.getId();

        int actualSteps = 0;
        String runStatus = TraceService.STATUS_FINISHED;
        String runError = null;

        try {
            for (int i = 0; i < MAX_STEPS && agentState != AgentState.FINISHED; i++) {
                // 当前步骤，用于实现 Agent Loop
                int currentStep = i + 1;
                step(currentStep);
                actualSteps = currentStep;
                if (currentStep >= MAX_STEPS) {
                    agentState = AgentState.FINISHED;
                    log.warn("Max steps reached, stopping agent");
                }
            }
            agentState = AgentState.FINISHED;
        } catch (Exception e) {
            agentState = AgentState.ERROR;
            runStatus = TraceService.STATUS_ERROR;
            runError = e.getMessage();
            log.error("Error running agent", e);
            throw new RuntimeException("Error running agent", e);
        } finally {
            long totalLatencyMs = (System.nanoTime() - runStart) / 1_000_000L;
            traceService.endTrace(traceId, runStatus, actualSteps, totalLatencyMs,
                    totalPromptTokens, totalCompletionTokens, runError);
        }
    }

    // 从 chatMemory 中取最近一条 UserMessage，用于在 agent_trace 里记录触发本次运行的用户提问
    private String extractLastUserMessage() {
        List<Message> msgs = this.chatMemory.get(this.chatSessionId);
        if (msgs == null) return null;
        for (int i = msgs.size() - 1; i >= 0; i--) {
            Message m = msgs.get(i);
            if (m instanceof UserMessage um) {
                return um.getText();
            }
        }
        return null;
    }

    @Override
    public String toString() {
        return "JChatMind {" +
                "name = " + name + ",\n" +
                "description = " + description + ",\n" +
                "agentId = " + agentId + ",\n" +
                "systemPrompt = " + systemPrompt + "}";
    }
}
