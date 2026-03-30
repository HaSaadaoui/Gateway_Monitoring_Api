package com.amaris.gatewaymonitoring.controller;

import com.amaris.gatewaymonitoring.service.TtnAppStreamHub;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.web.bind.annotation.*;

import reactor.core.publisher.Flux;

@RestController
@RequestMapping("/api/monitoring")
public class SensorController {

    private final TtnAppStreamHub ttnAppStreamHub;

    public SensorController(TtnAppStreamHub ttnAppStreamHub) {
        this.ttnAppStreamHub = ttnAppStreamHub;
    }

    // Body: ["desk-03-81","co2-03-02", ...] ou [] pour tout recevoir
    @PostMapping(value = "/app/{appId}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<ServerSentEvent<String>> streamManyLive(
            @PathVariable String appId,
            @RequestBody(required = false) List<String> deviceIds
    ) {
        return ttnAppStreamHub.stream(appId, deviceIds);
    }

    // GET /api/monitoring/app/{appId}/uplinks?after=...&limit=200
    @GetMapping(value = "/app/{appId}/uplinks", produces = "application/x-ndjson")
    public Flux<String> latestSensorsData(
            @PathVariable String appId,
            @RequestParam Optional<Instant> after,
            @RequestParam(defaultValue = "200") int limit
    ) {
        return ttnAppStreamHub.probeGatewayDevicesFlux(appId, after, limit);
    }

    // GET /api/monitoring/app/{appId}/latest
    // Retourne instantanément le dernier message connu par device depuis le cache mémoire MQTT.
    // Utilisé pour l'affichage initial du plan 2D sans attendre le snapshot TTN.
    @GetMapping("/app/{appId}/latest")
    public ResponseEntity<Map<String, String>> getLatestCached(@PathVariable String appId) {
        Map<String, String> cache = ttnAppStreamHub.getLatestFromCache(appId);
        if (cache.isEmpty()) {
            return ResponseEntity.noContent().build();
        }
        return ResponseEntity.ok(cache);
    }

    // POST /api/monitoring/app/{appId}/stream/stop
    @PostMapping("/app/{appId}/stream/stop")
    public ResponseEntity<String> stopAppStream(@PathVariable String appId) {
        ttnAppStreamHub.stop(appId);
        return ResponseEntity.ok("Stopped hub stream for appId=" + appId);
    }
}
