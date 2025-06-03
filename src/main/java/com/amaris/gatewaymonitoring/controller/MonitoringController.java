package com.amaris.gatewaymonitoring.controller;

import com.amaris.gatewaymonitoring.service.MonitoringService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class MonitoringController {

    private final MonitoringService monitoringService;

    @Autowired
    public MonitoringController(MonitoringService monitoringService) {
        this.monitoringService = monitoringService;
    }

    @GetMapping("/monitoring/{id}")
    public ResponseEntity<String> getMonitoringData(@PathVariable String id) {
        System.out.println("TESSSSSSSSSSSSSSSSSSSSSSSSSSSSST");
        String monitoringData = monitoringService.getMonitoringData(id);
        return ResponseEntity.ok(monitoringData);
    }

}
