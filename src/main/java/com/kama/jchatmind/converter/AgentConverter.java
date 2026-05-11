package com.kama.jchatmind.converter;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.kama.jchatmind.model.dto.AgentDTO;
import com.kama.jchatmind.model.entity.Agent;
import com.kama.jchatmind.model.request.CreateAgentRequest;
import com.kama.jchatmind.model.request.UpdateAgentRequest;
import com.kama.jchatmind.model.vo.AgentVO;
import lombok.AllArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.Collections;
import java.util.List;

@Component
@AllArgsConstructor
public class AgentConverter {

    private final ObjectMapper objectMapper;

    public Agent toEntity(AgentDTO agentDTO) throws JsonProcessingException {
        Assert.notNull(agentDTO, "AgentDTO cannot be null");
        Assert.notNull(agentDTO.getAllowedTools(), "Allowed tools cannot be null");
        Assert.notNull(agentDTO.getAllowedKbs(), "Allowed kbs cannot be null");
        Assert.notNull(agentDTO.getChatOptions(), "Chat options cannot be null");
        Assert.notNull(agentDTO.getModel(), "Model cannot be null");

        List<String> allowedSkills = agentDTO.getAllowedSkills() == null
                ? Collections.emptyList()
                : agentDTO.getAllowedSkills();

        return Agent.builder()
                .id(agentDTO.getId())
                .name(agentDTO.getName())
                .description(agentDTO.getDescription())
                .systemPrompt(agentDTO.getSystemPrompt())
                .model(agentDTO.getModel().getModelName())
                .allowedTools(objectMapper.writeValueAsString(agentDTO.getAllowedTools()))
                .allowedKbs(objectMapper.writeValueAsString(agentDTO.getAllowedKbs()))
                .allowedSkills(objectMapper.writeValueAsString(allowedSkills))
                .chatOptions(objectMapper.writeValueAsString(agentDTO.getChatOptions()))
                .createdAt(agentDTO.getCreatedAt())
                .updatedAt(agentDTO.getUpdatedAt())
                .build();
    }

    public AgentDTO toDTO(Agent agent) throws JsonProcessingException {
        Assert.notNull(agent, "Agent cannot be null");
        Assert.notNull(agent.getAllowedTools(), "Allowed tools cannot be null");
        Assert.notNull(agent.getAllowedKbs(), "Allowed kbs cannot be null");
        Assert.notNull(agent.getChatOptions(), "Chat options cannot be null");
        Assert.notNull(agent.getModel(), "Model cannot be null");

        List<String> allowedSkills = StringUtils.hasText(agent.getAllowedSkills())
                ? objectMapper.readValue(agent.getAllowedSkills(), new TypeReference<List<String>>() {})
                : Collections.emptyList();

        return AgentDTO.builder()
                .id(agent.getId())
                .name(agent.getName())
                .description(agent.getDescription())
                .systemPrompt(agent.getSystemPrompt())
                .model(AgentDTO.ModelType.fromModelName(agent.getModel()))
                .allowedTools(objectMapper.readValue(agent.getAllowedTools(), new TypeReference<>(){}))
                .allowedKbs(objectMapper.readValue(agent.getAllowedKbs(), new TypeReference<>(){}))
                .allowedSkills(allowedSkills)
                .chatOptions(objectMapper.readValue(agent.getChatOptions(), AgentDTO.ChatOptions.class))
                .createdAt(agent.getCreatedAt())
                .updatedAt(agent.getUpdatedAt())
                .build();
    }

    public AgentVO toVO(AgentDTO dto) {
        return AgentVO.builder()
                .id(dto.getId())
                .name(dto.getName())
                .description(dto.getDescription())
                .systemPrompt(dto.getSystemPrompt())
                .model(dto.getModel())
                .allowedTools(dto.getAllowedTools())
                .allowedKbs(dto.getAllowedKbs())
                .allowedSkills(dto.getAllowedSkills() == null ? Collections.emptyList() : dto.getAllowedSkills())
                .chatOptions(dto.getChatOptions())
                .build();
    }

    public AgentVO toVO(Agent agent) throws JsonProcessingException {
        return toVO(toDTO(agent));
    }

    public AgentDTO toDTO(CreateAgentRequest request) {
        Assert.notNull(request, "CreateAgentRequest cannot be null");
        Assert.notNull(request.getAllowedTools(), "Allowed tools cannot be null");
        Assert.notNull(request.getAllowedKbs(), "Allowed kbs cannot be null");
        Assert.notNull(request.getChatOptions(), "Chat options cannot be null");
        Assert.notNull(request.getModel(), "Model cannot be null");

        return AgentDTO.builder()
                .name(request.getName())
                .description(request.getDescription())
                .systemPrompt(request.getSystemPrompt())
                .model(AgentDTO.ModelType.fromModelName(request.getModel()))
                .allowedTools(request.getAllowedTools())
                .allowedKbs(request.getAllowedKbs())
                .allowedSkills(request.getAllowedSkills() == null ? Collections.emptyList() : request.getAllowedSkills())
                .chatOptions(request.getChatOptions())
                .build();
    }

    public void updateDTOFromRequest(AgentDTO dto, UpdateAgentRequest request) {
        Assert.notNull(dto, "AgentDTO cannot be null");
        Assert.notNull(request, "UpdateAgentRequest cannot be null");

        if (request.getName() != null) {
            dto.setName(request.getName());
        }
        if (request.getDescription() != null) {
            dto.setDescription(request.getDescription());
        }
        if (request.getSystemPrompt() != null) {
            dto.setSystemPrompt(request.getSystemPrompt());
        }
        if (request.getModel() != null) {
            dto.setModel(AgentDTO.ModelType.fromModelName(request.getModel()));
        }
        if (request.getAllowedTools() != null) {
            dto.setAllowedTools(request.getAllowedTools());
        }
        if (request.getAllowedKbs() != null) {
            dto.setAllowedKbs(request.getAllowedKbs());
        }
        if (request.getAllowedSkills() != null) {
            dto.setAllowedSkills(request.getAllowedSkills());
        }
        if (request.getChatOptions() != null) {
            dto.setChatOptions(request.getChatOptions());
        }
    }
}
