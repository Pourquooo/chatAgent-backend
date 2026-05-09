package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.AgentTrace;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * @description 针对表【agent_trace】的数据库操作Mapper
 * @Entity com.kama.jchatmind.model.entity.AgentTrace
 */
@Mapper
public interface AgentTraceMapper {

    int insert(AgentTrace trace);

    AgentTrace selectById(String id);

    List<AgentTrace> selectBySessionId(String sessionId);

    int updateById(AgentTrace trace);
}
