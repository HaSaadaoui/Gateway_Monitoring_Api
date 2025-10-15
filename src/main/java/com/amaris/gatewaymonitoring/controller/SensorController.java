package com.amaris.gatewaymonitoring.controller;

import com.amaris.gatewaymonitoring.service.AggregatorSensorService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api")
public class SensorController {

    private final AggregatorSensorService aggregatorSensorService;

    public SensorController(AggregatorSensorService aggregatorSensorService) {
        this.aggregatorSensorService = aggregatorSensorService;
    }

    /**
     * Écoute en temps réel un capteur TTN via SSE
     * Exemple URL : /api/monitoring/sensor/occup-vs70-03-04?threadId=thread1&appId=my-ttn-app
     */
    @GetMapping(value = "/monitoring/sensor/{appId}/{deviceId}", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<String> streamSensor(@PathVariable String appId,
                                     @PathVariable String deviceId,
                                     @RequestParam String threadId) {
        return Flux.create(sink ->
                aggregatorSensorService.aggregateSensorMonitoring(appId, deviceId, threadId, sink::next)
        );
    }

    /**
     * Arrête l'écoute d'un capteur TTN
     */
    @GetMapping("/monitoring/sensor/stop/{deviceId}")
    public ResponseEntity<String> stopSensor(@PathVariable String deviceId,
                                             @RequestParam String threadId) {
        aggregatorSensorService.stopSensorMonitoring(threadId);
        return ResponseEntity.ok("Monitoring stopped for sensor " + deviceId);
    }
}
