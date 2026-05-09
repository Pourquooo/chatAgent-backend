package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.AgentStepTrace;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * @description 针对表【agent_step_trace】的数据库操作Mapper
 * @Entity com.kama.jchatmind.model.entity.AgentStepTrace
 */
@Mapper
public interface AgentStepTraceMapper {

    int insert(AgentStepTrace step);

    List<AgentStepTrace> selectByTraceId(String traceId);

    int updateById(AgentStepTrace step);
}
