package com.claude.web.entity;

import java.time.LocalDateTime;

public class AiSession {
    private String appId;
    private String userId;
    private String userCwd;
    private String sessionId;
    private String sessionTitle;
    private String sessionStatus;
    private LocalDateTime createTime;

    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getUserCwd() { return userCwd; }
    public void setUserCwd(String userCwd) { this.userCwd = userCwd; }
    public String getSessionId() { return sessionId; }
    public void setSessionId(String sessionId) { this.sessionId = sessionId; }
    public String getSessionTitle() { return sessionTitle; }
    public void setSessionTitle(String sessionTitle) { this.sessionTitle = sessionTitle; }
    public String getSessionStatus() { return sessionStatus; }
    public void setSessionStatus(String sessionStatus) { this.sessionStatus = sessionStatus; }
    public LocalDateTime getCreateTime() { return createTime; }
    public void setCreateTime(LocalDateTime createTime) { this.createTime = createTime; }
}