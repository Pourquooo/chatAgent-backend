package com.kama.jchatmind.mapper;

import com.kama.jchatmind.model.entity.McpServer;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;

import java.util.List;

/**
 * @description 针对表【mcp_server】的数据库操作Mapper
 * @Entity com.kama.jchatmind.model.entity.McpServer
 */
@Mapper
public interface McpServerMapper {

    int insert(McpServer server);

    McpServer selectById(@Param("id") String id);

    List<McpServer> selectAll();

    List<McpServer> selectByIdBatch(@Param("ids") List<String> ids);

    int deleteById(@Param("id") String id);

    int updateById(McpServer server);
}
