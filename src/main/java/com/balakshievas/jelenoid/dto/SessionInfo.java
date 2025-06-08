package com.balakshievas.jelenoid.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SessionInfo {
    private String containerId;
    private String remoteSessionId;
}
