package com.balakshievas.jelenoid.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class SessionInfo {

    private String id;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private String platform;
    private String version;
    private String status;
    private String endedBy;

}
