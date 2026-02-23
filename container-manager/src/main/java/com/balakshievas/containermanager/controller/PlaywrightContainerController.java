package com.balakshievas.containermanager.controller;

import com.balakshievas.containermanager.dto.ContainerInfo;
import com.balakshievas.containermanager.service.PlaywrightContainerManagerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/containers/playwright")
public class PlaywrightContainerController {

    @Autowired
    private PlaywrightContainerManagerService playwrightContainerManagerService;

    @PostMapping
    public ResponseEntity<ContainerInfo> startPlaywrightContainer(@RequestParam String image,
                                                                  @RequestParam String playwrightVersion) {
        return ResponseEntity.ok(playwrightContainerManagerService.startPlaywrightContainer(image, playwrightVersion));
    }

}
