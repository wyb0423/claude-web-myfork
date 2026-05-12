package com.claude.web.mapper;

import com.claude.web.entity.AiSession;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;
import java.util.List;

@Mapper
public interface AiSessionMapper {
    List<AiSession> selectByAppIdAndUserId(@Param("appId") String appId, @Param("userId") String userId);
    AiSession selectBySessionId(@Param("sessionId") String sessionId);
    AiSession selectLatestByUserId(@Param("userId") String userId);
    List<AiSession> searchSessions(
        @Param("appId") String appId,
        @Param("userId") String userId,
        @Param("userCwd") String userCwd,
        @Param("sessionId") String sessionId,
        @Param("sessionTitle") String sessionTitle,
        @Param("sessionStatus") String sessionStatus,
        @Param("createTimeStart") LocalDateTime createTimeStart,
        @Param("createTimeEnd") LocalDateTime createTimeEnd
    );
    void insert(AiSession session);
    void update(AiSession session);
    void deleteBySessionId(@Param("sessionId") String sessionId);
}