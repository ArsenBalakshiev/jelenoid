package com.balakshievas.containermanager.controller;

import com.balakshievas.containermanager.service.SeleniumContainerManagerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import java.io.IOException;

@RestController
@RequestMapping("/api/containers")
public class GeneralContainerController {

    @Autowired
    private SeleniumContainerManagerService seleniumContainerManagerService;

    @PostMapping( value = { "/{containerId}/file"},
            consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<String> uploadFileMultipart(
            @PathVariable String containerId,
            @RequestParam("file") MultipartFile file) throws IOException {

        return ResponseEntity.ok(seleniumContainerManagerService.copyFileToContainer(containerId, file.getBytes()));
    }

    @GetMapping(path = "/{containerId}/logs", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter streamLogs(@PathVariable String containerId) {
        return seleniumContainerManagerService.streamContainerLogs(containerId);
    }

    @DeleteMapping
    public ResponseEntity<Boolean> killContainer(@RequestParam String containerId) {
        return ResponseEntity.ok(seleniumContainerManagerService.stopContainer(containerId));
    }


}
