package com.balakshievas.jelenoid.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@AllArgsConstructor
@NoArgsConstructor
public class BrowserInfo {

    private String name;
    private String version;
    private String dockerImageName;
    private Boolean isDefault;

}
