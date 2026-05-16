package com.claude.web.mapper;

import com.claude.web.entity.User;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;

@Mapper
public interface UserMapper {
    User findByUsername(@Param("username") String username);
    int countAll();
    void insert(User user);
    void updateLastLoginTime(@Param("id") Long id, @Param("lastLoginTime") LocalDateTime lastLoginTime);
}
