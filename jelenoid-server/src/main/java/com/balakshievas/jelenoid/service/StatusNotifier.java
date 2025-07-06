package com.balakshievas.jelenoid.service;

import com.balakshievas.jelenoid.dto.StatusChangedEvent;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

@Component
public class StatusNotifier {

    private StatusService statusService;
    private EmitterService emitterService;

    @Autowired
    public void setStatusService(StatusService statusService) {
        this.statusService = statusService;
    }

    @Autowired
    public void setEmitterService(EmitterService emitterService) {
        this.emitterService = emitterService;
    }

    @EventListener
    public void onStatusChanged(StatusChangedEvent event) {
        emitterService.dispatch(statusService.buildStatus());
    }
}
