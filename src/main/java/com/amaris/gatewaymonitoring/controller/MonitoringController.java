package com.amaris.gatewaymonitoring.controller;

import com.amaris.gatewaymonitoring.service.MonitoringService;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api")
public class MonitoringController {

    private final MonitoringService monitoringService;

    @Autowired
    public MonitoringController(MonitoringService monitoringService) {
        this.monitoringService = monitoringService;
    }

    @GetMapping(value="/monitoring/{id}", produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamMonitoringData(@PathVariable String id) {
        System.out.println("TESSSSSSSSSSSSSSSSSSSSSSSSSSSSST");
        return Flux.create(sink -> {
            monitoringService.startMonitoring(id, json -> {
                sink.next(json); // envoie chaque json reçu au client
            });
        });
    }

    @DeleteMapping("/monitoring/stop")
    public ResponseEntity<String> stopStreamMonitoring() {
        monitoringService.stopMonitoring();
        return ResponseEntity.ok("Arrêt de l'écoute MQTT demandé");
    }

}
