package com.balakshievas.jelenoid.controller;

import com.balakshievas.jelenoid.dto.BrowserInfo;
import com.balakshievas.jelenoid.service.BrowserManagerService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/browsers")
public class BrowserManagerController {

    @Autowired
    private BrowserManagerService browserManagerService;

    @GetMapping
    public ResponseEntity<List<BrowserInfo>> getBrowsers() {
        return ResponseEntity.ok(browserManagerService.getAllBrowsers());
    }

    @PutMapping("/add")
    public ResponseEntity<BrowserInfo> saveBrowser(@RequestBody BrowserInfo browserInfo) {
        BrowserInfo browserInfoSaved = browserManagerService.addBrowser(browserInfo);
        return ResponseEntity.ok(browserInfoSaved);
    }

    @DeleteMapping("/delete")
    public ResponseEntity<BrowserInfo> deleteBrowser(@RequestParam String browserName,
                                                     @RequestParam String browserVersion) {
        BrowserInfo browserInfoDeleted = browserManagerService.deleteBrowser(browserName, browserVersion);
        return ResponseEntity.ok(browserInfoDeleted);
    }

}
