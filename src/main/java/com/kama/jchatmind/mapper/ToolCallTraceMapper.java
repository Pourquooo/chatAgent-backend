package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.ToolCallTrace;
import org.apache.ibatis.annotations.Mapper;

import java.util.List;

/**
 * @description 针对表【tool_call_trace】的数据库操作Mapper
 * @Entity com.kama.jchatmind.model.entity.ToolCallTrace
 */
@Mapper
public interface ToolCallTraceMapper {

    int insert(ToolCallTrace toolCall);

    List<ToolCallTrace> selectByTraceId(String traceId);

    List<ToolCallTrace> selectByStepId(String stepId);

    int updateById(ToolCallTrace toolCall);
}
