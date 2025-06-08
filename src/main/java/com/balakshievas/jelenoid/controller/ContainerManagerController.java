package com.balakshievas.jelenoid.controller;

import com.balakshievas.jelenoid.dto.ContainerInfo;
import com.balakshievas.jelenoid.service.ContainerManagerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api/containers")
public class ContainerManagerController {

    @Autowired
    private ContainerManagerService containerManagerService;

    @GetMapping
    public ResponseEntity<Map<String, ContainerInfo>> getActiveContainers() {
        return ResponseEntity.ok(containerManagerService.getActiveContainers());
    }

    @PutMapping("/create")
    public ResponseEntity<ContainerInfo> createContainer(@RequestBody String imageName) {
        return ResponseEntity.ok(containerManagerService.startContainer(imageName));
    }

}
