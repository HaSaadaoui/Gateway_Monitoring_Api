package com.amaris.gatewaymonitoring.controller;

import com.amaris.gatewaymonitoring.service.AggregatorSensorService;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/monitoring")
public class SensorController {

    private final AggregatorSensorService aggregatorSensorService;

    public SensorController(AggregatorSensorService aggregatorSensorService) {
        this.aggregatorSensorService = aggregatorSensorService;
    }

    // =========================================================
    // ✅ SSE LIVE multi-devices (200+) : snapshot TTN direct + live
    // =========================================================
    // POST /api/monitoring/app/{appId}/stream?clientId=xxx
    // Body: ["desk-03-81","co2-03-02", ...] ou [] pour tout recevoir
    @PostMapping(value = "/app/{appId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamManyLive(
            @PathVariable String appId,
            @RequestParam(required = false, defaultValue = "client") String clientId,
            @RequestBody(required = false) List<String> deviceIds
    ) {
        return aggregatorSensorService.streamManyLive(appId, clientId, deviceIds);
    }

    // =========================================================
    // ✅ DEBUG / HISTORIQUE BORNE (NDJSON)
    // =========================================================
    // GET /api/monitoring/app/{appId}/uplinks?after=...&limit=200
    @GetMapping(value = "/app/{appId}/uplinks", produces = "application/x-ndjson")
    public Flux<String> latestSensorsData(
            @PathVariable String appId,
            @RequestParam Optional<Instant> after,
            @RequestParam(defaultValue = "200") int limit
    ) {
        return aggregatorSensorService.aggregateGatewayDevicesBounded(appId, after, limit);
    }

    // =========================================================
    // ✅ ADMIN : stop le poller d'une appId (coupe le hub)
    // =========================================================
    // POST /api/monitoring/app/{appId}/stream/stop
    @PostMapping("/app/{appId}/stream/stop")
    public ResponseEntity<String> stopAppStream(@PathVariable String appId) {
        aggregatorSensorService.stopAppStream(appId);
        return ResponseEntity.ok("Stopped hub stream for appId=" + appId);
    }
}
