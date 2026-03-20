package com.amaris.gatewaymonitoring.service;

import org.springframework.http.codec.ServerSentEvent;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Façade légère entre le controller et les services métier.
 * - SSE live  → TtnAppStreamHub (MQTT + snapshot HTTP)
 * - Debug NDJSON borné → SensorMonitoringService
 */
@Service
public class AggregatorSensorService {

    private final TtnAppStreamHub ttnAppStreamHub;
    private final SensorMonitoringService sensorMonitoringService;

    public AggregatorSensorService(
            TtnAppStreamHub ttnAppStreamHub,
            SensorMonitoringService sensorMonitoringService
    ) {
        this.ttnAppStreamHub = ttnAppStreamHub;
        this.sensorMonitoringService = sensorMonitoringService;
    }

    public Flux<ServerSentEvent<String>> streamManyLive(String appId, List<String> deviceIds) {
        return ttnAppStreamHub.stream(appId, deviceIds);
    }

    public void stopAppStream(String appId) {
        ttnAppStreamHub.stop(appId);
    }

    public Flux<String> aggregateGatewayDevicesBounded(String appId, Optional<Instant> after, int limit) {
        return sensorMonitoringService.probeGatewayDevicesFlux(appId, after, limit);
    }
}