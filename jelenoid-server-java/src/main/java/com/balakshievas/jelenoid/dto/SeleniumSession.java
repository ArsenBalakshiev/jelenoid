package com.balakshievas.jelenoid.dto;

import lombok.Data;

@Data
public class SeleniumSession extends Session {
    private final String hubSessionId;
    private final String remoteSessionId;
    private final String browserName;
    private final boolean vncEnabled;

    public SeleniumSession(String hubSessionId, String remoteSessionId, ContainerInfo containerInfo,
                           String browserName, String version, boolean vncEnabled) {
        this.hubSessionId = hubSessionId;
        this.remoteSessionId = remoteSessionId;
        super.setContainerInfo(containerInfo);
        this.browserName = browserName;
        super.setVersion(version);
        this.vncEnabled = vncEnabled;
    }
}
