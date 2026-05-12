package com.claude.web.entity;

public class AiApp {
    private String appId;
    private String accessTake;
    private String serveType;
    private String serveIp;
    private String servePort;

    public String getAppId() { return appId; }
    public void setAppId(String appId) { this.appId = appId; }
    public String getAccessTake() { return accessTake; }
    public void setAccessTake(String accessTake) { this.accessTake = accessTake; }
    public String getServeType() { return serveType; }
    public void setServeType(String serveType) { this.serveType = serveType; }
    public String getServeIp() { return serveIp; }
    public void setServeIp(String serveIp) { this.serveIp = serveIp; }
    public String getServePort() { return servePort; }
    public void setServePort(String servePort) { this.servePort = servePort; }
}