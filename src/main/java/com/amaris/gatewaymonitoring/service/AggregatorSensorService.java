package com.amaris.gatewaymonitoring.service;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;

@Service
public class AggregatorSensorService {

    // legacy: debug / NDJSON borné
    private final SensorMonitoringService sensorMonitoringService;

    // nouveau hub: snapshot TTN direct + live app-level filtré
    private final TtnAppStreamHub ttnAppStreamHub;

    public AggregatorSensorService(
            SensorMonitoringService sensorMonitoringService,
            TtnAppStreamHub ttnAppStreamHub
    ) {
        this.sensorMonitoringService = sensorMonitoringService;
        this.ttnAppStreamHub = ttnAppStreamHub;
    }

    // =========================
    // ✅ Nouveau hub multi (SSE)
    // =========================
    public Flux<ServerSentEvent<String>> streamManyLive(String appId, String clientId, List<String> deviceIds) {
        return ttnAppStreamHub.stream(appId, clientId, deviceIds);
    }

    // =========================
    // ✅ Stop app stream (si tu as une route stop)
    // =========================
    public void stopAppStream(String appId) {
        ttnAppStreamHub.stop(appId);
    }

    // =========================
    // ✅ Debug / NDJSON borné
    // (nécessite que SensorMonitoringService expose probeGatewayDevicesFlux)
    // =========================
    public Flux<String> aggregateGatewayDevicesBounded(String appId, Optional<Instant> after, int limit) {
        return sensorMonitoringService.probeGatewayDevicesFlux(appId, after, limit);
    }
}
