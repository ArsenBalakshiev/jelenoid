package com.balakshievas.containermanager.controller;

import com.balakshievas.containermanager.dto.ContainerInfo;
import com.balakshievas.containermanager.service.SeleniumContainerManagerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/containers/selenium")
public class SeleniumContainerController {

    @Autowired
    private SeleniumContainerManagerService seleniumContainerManagerService;

    @PostMapping
    public ResponseEntity<ContainerInfo> startSeleniumContainer(@RequestParam String image,
                                                                @RequestParam Boolean isVncEnabled) {
        return ResponseEntity.ok(seleniumContainerManagerService.startContainer(image, isVncEnabled));
    }

}
