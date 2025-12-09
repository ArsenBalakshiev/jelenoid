package com.balakshievas.containermanager.controller;

import com.balakshievas.containermanager.dto.ContainerInfo;
import com.balakshievas.containermanager.service.SeleniumContainerManagerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/containers/selenium")
public class SeleniumContainerController {

    @Autowired
    private SeleniumContainerManagerService seleniumContainerManagerService;

    @PostMapping
    public ContainerInfo startSeleniumContainer(@RequestParam String image, @RequestParam Boolean isVncEnabled) {
        return seleniumContainerManagerService.startContainer(image, isVncEnabled);
    }

    @DeleteMapping
    public ContainerInfo killSeleniumContainer(@RequestParam String containerId) {
        return seleniumContainerManagerService.stopContainer(containerId);
    }

}
